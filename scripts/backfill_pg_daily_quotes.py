#!/usr/bin/env python3
import csv
import json
import os
import sys
import time
from datetime import date, datetime, timedelta
from pathlib import Path
from urllib import request

API_URL = "http://api.tushare.pro"
DAILY_FIELDS = "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount"
FAILED_FILE = Path("logs/backfill_daily_failed.csv")


def log(*args):
    print(*args, flush=True)


def load_env():
    path = Path(".env")
    if not path.exists():
        raise RuntimeError("缺少 .env，请在项目根目录创建 .env（可参考 .env.example）")
    values = {}
    for raw in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def cfg(env, key, default=""):
    return os.environ.get(key, env.get(key, default)).strip()


def parse_date(value):
    if isinstance(value, date):
        return value
    text = str(value or "").strip()
    if len(text) >= 10 and text[4] == "-" and text[7] == "-":
        return datetime.strptime(text[:10], "%Y-%m-%d").date()
    if len(text) == 8 and text.isdigit():
        return datetime.strptime(text, "%Y%m%d").date()
    return None


def compact_date(value):
    d = parse_date(value)
    return d.strftime("%Y%m%d") if d else str(value or "").strip()


def qident(name):
    return '"' + name.replace('"', '""') + '"'


def fnum(value):
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def connect_postgres(env):
    if cfg(env, "DB_TYPE", "postgresql").lower() != "postgresql":
        raise RuntimeError("DB_TYPE 必须为 postgresql")
    try:
        import psycopg2
    except ImportError as exc:
        raise RuntimeError("缺少 psycopg2，请安装 psycopg2-binary") from exc
    return psycopg2.connect(
        host=cfg(env, "DB_HOST", "172.23.112.1"),
        port=int(cfg(env, "DB_PORT", "5432")),
        user=cfg(env, "DB_USER", "postgres"),
        password=cfg(env, "DB_PASSWORD"),
        dbname=cfg(env, "DB_NAME", "stock_db"),
    )


class Schema:
    def __init__(self, conn):
        self.conn = conn
        self.columns = self._load_columns()

    def _load_columns(self):
        cur = self.conn.cursor()
        cur.execute(
            """
            SELECT table_name, column_name, data_type
            FROM information_schema.columns
            WHERE table_schema = 'public'
            ORDER BY table_name, ordinal_position
            """
        )
        out = {}
        for table, column, data_type in cur.fetchall():
            out.setdefault(table, {})[column] = data_type
        return out

    def has_table(self, table):
        return table in self.columns

    def has_column(self, table, column):
        return column in self.columns.get(table, {})

    def pick(self, table, *names):
        cols = self.columns.get(table, {})
        lower = {c.lower(): c for c in cols}
        for name in names:
            found = lower.get(name.lower())
            if found:
                return found
        return None

    def type_of(self, table, column):
        return self.columns.get(table, {}).get(column, "")


def post_tushare_daily(token, ts_code, start_date, end_date):
    body = json.dumps(
        {
            "api_name": "daily",
            "token": token,
            "params": {"ts_code": ts_code, "start_date": start_date, "end_date": end_date},
            "fields": DAILY_FIELDS,
        },
        ensure_ascii=False,
    ).encode("utf-8")
    delays = [5, 15, 30]
    last_error = None
    for attempt in range(3):
        try:
            req = request.Request(API_URL, data=body, headers={"Content-Type": "application/json; charset=utf-8"}, method="POST")
            with request.urlopen(req, timeout=180) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
            if payload.get("code") != 0:
                raise RuntimeError(payload.get("msg") or f"Tushare code={payload.get('code')}")
            data = payload.get("data") or {}
            fields = data.get("fields") or []
            items = data.get("items") or []
            return [{field: row[i] if i < len(row) else None for i, field in enumerate(fields)} for row in items]
        except Exception as exc:
            last_error = exc
            if attempt < 2:
                time.sleep(delays[attempt])
    raise last_error


def stock_codes(conn, schema, limit):
    if not schema.has_table("stock_basic"):
        raise RuntimeError("未找到 stock_basic 表")
    ts_col = schema.pick("stock_basic", "ts_code", "symbol", "tsCode")
    if not ts_col:
        raise RuntimeError("stock_basic 缺少 ts_code/symbol 字段")
    cur = conn.cursor()
    cur.execute(f"SELECT {qident(ts_col)} FROM stock_basic WHERE {qident(ts_col)} IS NOT NULL ORDER BY {qident(ts_col)} LIMIT %s", (limit,))
    return [str(row[0] or "").strip().upper() for row in cur.fetchall() if str(row[0] or "").strip()]


def earliest_trade_date(conn, schema, ts_code):
    ts_col = schema.pick("daily_quotes", "ts_code", "symbol", "tsCode")
    date_col = schema.pick("daily_quotes", "trade_date", "tradeDate")
    if not ts_col or not date_col:
        raise RuntimeError("daily_quotes 缺少 ts_code/symbol 或 trade_date 字段")
    cur = conn.cursor()
    cur.execute(f"SELECT MIN({qident(date_col)}) FROM daily_quotes WHERE {qident(ts_col)} = %s", (ts_code,))
    row = cur.fetchone()
    return parse_date(row[0]) if row and row[0] else None


def convert_trade_date_for_db(schema, value):
    d = parse_date(value)
    if schema.type_of("daily_quotes", schema.pick("daily_quotes", "trade_date", "tradeDate")) == "date":
        return d
    return d.strftime("%Y%m%d") if d else str(value or "").strip()


def upsert_daily_rows(conn, schema, rows):
    if not rows:
        return 0
    table = "daily_quotes"
    mapping = {
        "ts_code": schema.pick(table, "ts_code", "symbol", "tsCode"),
        "trade_date": schema.pick(table, "trade_date", "tradeDate"),
        "open": schema.pick(table, "open"),
        "high": schema.pick(table, "high"),
        "low": schema.pick(table, "low"),
        "close": schema.pick(table, "close"),
        "pre_close": schema.pick(table, "pre_close", "preClose"),
        "change": schema.pick(table, "change", "change_value", "changeValue"),
        "pct_chg": schema.pick(table, "pct_chg", "pctChg"),
        "vol": schema.pick(table, "vol", "volume"),
        "amount": schema.pick(table, "amount"),
        "adj_type": schema.pick(table, "adj_type", "adjType"),
    }
    ts_col = mapping["ts_code"]
    date_col = mapping["trade_date"]
    adj_col = mapping["adj_type"]
    insert_cols = [col for key, col in mapping.items() if col and key != "adj_type"]
    if adj_col:
        insert_cols.append(adj_col)
    delete_sql = f"DELETE FROM {qident(table)} WHERE {qident(ts_col)} = %s AND {qident(date_col)} = %s"
    if adj_col:
        delete_sql += f" AND {qident(adj_col)} = 'qfq'"
    insert_sql = f"INSERT INTO {qident(table)} ({', '.join(qident(c) for c in insert_cols)}) VALUES ({', '.join(['%s'] * len(insert_cols))})"
    delete_rows = []
    insert_rows = []
    for row in rows:
        ts_code = str(row.get("ts_code") or "").strip().upper()
        trade_date = convert_trade_date_for_db(schema, row.get("trade_date"))
        if not ts_code or not trade_date:
            continue
        values_by_column = {
            mapping["ts_code"]: ts_code,
            mapping["trade_date"]: trade_date,
            mapping["open"]: fnum(row.get("open")),
            mapping["high"]: fnum(row.get("high")),
            mapping["low"]: fnum(row.get("low")),
            mapping["close"]: fnum(row.get("close")),
            mapping["pre_close"]: fnum(row.get("pre_close")),
            mapping["change"]: fnum(row.get("change")),
            mapping["pct_chg"]: fnum(row.get("pct_chg")),
            mapping["vol"]: fnum(row.get("vol")),
            mapping["amount"]: fnum(row.get("amount")),
        }
        if adj_col:
            values_by_column[adj_col] = "qfq"
        delete_rows.append((ts_code, trade_date))
        insert_rows.append(tuple(values_by_column.get(col) for col in insert_cols))
    cur = conn.cursor()
    if delete_rows:
        cur.executemany(delete_sql, delete_rows)
    if insert_rows:
        cur.executemany(insert_sql, insert_rows)
    conn.commit()
    return len(insert_rows)


def log_failure(ts_code, start_date, end_date, reason):
    FAILED_FILE.parent.mkdir(parents=True, exist_ok=True)
    exists = FAILED_FILE.exists()
    with FAILED_FILE.open("a", newline="", encoding="utf-8-sig") as f:
        writer = csv.writer(f)
        if not exists:
            writer.writerow(["ts_code", "start_date", "end_date", "reason", "failed_at"])
        writer.writerow([ts_code, start_date, end_date, str(reason)[:500], datetime.now().strftime("%Y-%m-%d %H:%M:%S")])


def main():
    env = load_env()
    token = cfg(env, "TUSHARE_TOKEN")
    if not token:
        raise RuntimeError(".env 缺少 TUSHARE_TOKEN，无法补齐历史日线")
    max_scan_count = int(cfg(env, "MAX_SCAN_COUNT", "20") or "20")
    start_date_text = cfg(env, "START_DATE", "20160508") or "20160508"
    target_start = parse_date(start_date_text)
    if not target_start:
        raise RuntimeError("START_DATE 必须是 yyyyMMdd 或 yyyy-MM-dd")
    end_override = cfg(env, "END_DATE", "")
    override_end = parse_date(end_override) if end_override else None
    conn = connect_postgres(env)
    try:
        schema = Schema(conn)
        if not schema.has_table("daily_quotes"):
            raise RuntimeError("未找到 daily_quotes 表")
        codes = stock_codes(conn, schema, max_scan_count)
        log("PostgreSQL 连接成功: True")
        log("本次最多处理股票数:", len(codes))
        log("目标最早日期:", target_start.strftime("%Y%m%d"))
        total_inserted = 0
        failed = 0
        skipped = 0
        for index, ts_code in enumerate(codes, start=1):
            log(f"processing {index}/{len(codes)} {ts_code}")
            try:
                earliest = earliest_trade_date(conn, schema, ts_code)
                log("current earliest:", compact_date(earliest) if earliest else "—")
                if earliest and earliest <= target_start:
                    log("skip: already enough history")
                    skipped += 1
                    continue
                if not earliest and not override_end:
                    raise RuntimeError("该股票库内无 daily_quotes，且未设置 END_DATE")
                range_end = override_end or (earliest - timedelta(days=1))
                if range_end < target_start:
                    log("skip: empty range")
                    skipped += 1
                    continue
                start_s = target_start.strftime("%Y%m%d")
                end_s = range_end.strftime("%Y%m%d")
                log(f"backfill range: {start_s} - {end_s}")
                rows = post_tushare_daily(token, ts_code, start_s, end_s)
                inserted = upsert_daily_rows(conn, schema, rows)
                total_inserted += inserted
                log("inserted rows:", inserted)
            except Exception as exc:
                conn.rollback()
                failed += 1
                log_failure(ts_code, target_start.strftime("%Y%m%d"), compact_date(override_end) if override_end else "", exc)
                log("failed:", type(exc).__name__)
        log("done")
        log("inserted total:", total_inserted)
        log("skipped:", skipped)
        log("failed:", failed)
        if failed:
            log("失败日志:", FAILED_FILE)
    finally:
        conn.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"执行失败: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
