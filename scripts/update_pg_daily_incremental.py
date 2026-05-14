#!/usr/bin/env python3
import json
import os
import sys
import time
import csv
from datetime import date, datetime, timedelta
from pathlib import Path
from urllib import request

API_URL = "http://api.tushare.pro"
DAILY_FIELDS = "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount"
FAILED_FILE = Path("logs/update_pg_daily_failed.csv")


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


def compact(value):
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


def columns(conn, table):
    cur = conn.cursor()
    cur.execute(
        """
        SELECT column_name, data_type
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = %s
        ORDER BY ordinal_position
        """,
        (table,),
    )
    return dict(cur.fetchall())


def post_tushare_daily(token, trade_date, timeout=180, retry=3):
    body = json.dumps(
        {
            "api_name": "daily",
            "token": token,
            "params": {"trade_date": trade_date},
            "fields": DAILY_FIELDS,
        },
        ensure_ascii=False,
    ).encode("utf-8")
    delays = [5, 15, 30]
    last_error = None
    for attempt in range(retry):
        try:
            req = request.Request(API_URL, data=body, headers={"Content-Type": "application/json; charset=utf-8"}, method="POST")
            with request.urlopen(req, timeout=timeout) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
            if payload.get("code") != 0:
                raise RuntimeError(payload.get("msg") or f"Tushare code={payload.get('code')}")
            data = payload.get("data") or {}
            fields = data.get("fields") or []
            items = data.get("items") or []
            return [{field: row[i] if i < len(row) else None for i, field in enumerate(fields)} for row in items]
        except Exception as exc:
            last_error = exc
            if attempt < retry - 1:
                time.sleep(delays[min(attempt, len(delays) - 1)])
    raise last_error


def existing_count(conn, trade_date):
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM daily_quotes WHERE trade_date = %s", (parse_date(trade_date) or trade_date,))
    return int(cur.fetchone()[0])


def upsert_daily(conn, rows):
    if not rows:
        return 0
    cols = columns(conn, "daily_quotes")
    has_adj = "adj_type" in cols
    insert_cols = ["ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "change", "pct_chg", "vol", "amount"]
    if has_adj:
        insert_cols.append("adj_type")
    delete_sql = "DELETE FROM daily_quotes WHERE ts_code = %s AND trade_date = %s"
    if has_adj:
        delete_sql += " AND adj_type = 'qfq'"
    insert_sql = f"INSERT INTO daily_quotes ({', '.join(qident(c) for c in insert_cols)}) VALUES ({', '.join(['%s'] * len(insert_cols))})"
    delete_rows = []
    insert_rows = []
    for row in rows:
        ts_code = str(row.get("ts_code") or "").strip().upper()
        trade_date = parse_date(row.get("trade_date"))
        if not ts_code or not trade_date:
            continue
        delete_rows.append((ts_code, trade_date))
        values = [
            ts_code,
            trade_date,
            fnum(row.get("open")),
            fnum(row.get("high")),
            fnum(row.get("low")),
            fnum(row.get("close")),
            fnum(row.get("pre_close")),
            fnum(row.get("change")),
            fnum(row.get("pct_chg")),
            fnum(row.get("vol")),
            fnum(row.get("amount")),
        ]
        if has_adj:
            values.append("qfq")
        insert_rows.append(tuple(values))
    cur = conn.cursor()
    cur.executemany(delete_sql, delete_rows)
    cur.executemany(insert_sql, insert_rows)
    conn.commit()
    return len(insert_rows)


def log_failure(trade_date, reason):
    FAILED_FILE.parent.mkdir(parents=True, exist_ok=True)
    exists = FAILED_FILE.exists()
    with FAILED_FILE.open("a", newline="", encoding="utf-8-sig") as f:
        writer = csv.writer(f)
        if not exists:
            writer.writerow(["trade_date", "reason", "failed_at"])
        writer.writerow([trade_date, str(reason)[:500], datetime.now().strftime("%Y-%m-%d %H:%M:%S")])


def default_dates(conn):
    cur = conn.cursor()
    cur.execute("SELECT MAX(trade_date) FROM daily_quotes")
    latest = parse_date(cur.fetchone()[0])
    start = latest + timedelta(days=1) if latest else date.today()
    end = date.today()
    return start, end


def main():
    env = load_env()
    token = cfg(env, "TUSHARE_TOKEN")
    if not token:
        raise RuntimeError(".env 缺少 TUSHARE_TOKEN，无法更新 daily_quotes")
    conn = connect_postgres(env)
    try:
        start_default, end_default = default_dates(conn)
        start = parse_date(cfg(env, "START_DATE", start_default.strftime("%Y%m%d")))
        end = parse_date(cfg(env, "END_DATE", end_default.strftime("%Y%m%d")))
        if not start or not end:
            raise RuntimeError("START_DATE/END_DATE 必须是 yyyyMMdd 或 yyyy-MM-dd")
        if start > end:
            print("无需更新：START_DATE 晚于 END_DATE")
            return
        force = cfg(env, "FORCE", "0") == "1"
        timeout = int(cfg(env, "TUSHARE_TIMEOUT", "180") or "180")
        retry = int(cfg(env, "TUSHARE_RETRY", "3") or "3")
        total_days = (end - start).days + 1
        total_rows = 0
        updated_days = 0
        empty_days = 0
        skipped_days = 0
        failed_days = 0
        print("PostgreSQL 连接成功: True", flush=True)
        print("更新日期范围:", start.strftime("%Y%m%d"), "-", end.strftime("%Y%m%d"), flush=True)
        day = start
        idx = 0
        while day <= end:
            idx += 1
            trade_date = day.strftime("%Y%m%d")
            if not force and existing_count(conn, trade_date) > 0:
                skipped_days += 1
                print(f"processing {idx}/{total_days} {trade_date} skip existing", flush=True)
                day += timedelta(days=1)
                continue
            print(f"processing {idx}/{total_days} {trade_date}", flush=True)
            try:
                rows = post_tushare_daily(token, trade_date, timeout=timeout, retry=retry)
                inserted = upsert_daily(conn, rows)
                total_rows += inserted
                if inserted:
                    updated_days += 1
                else:
                    empty_days += 1
                print("inserted rows:", inserted, flush=True)
            except Exception as exc:
                conn.rollback()
                failed_days += 1
                log_failure(trade_date, exc)
                print("failed:", type(exc).__name__, str(exc)[:120], flush=True)
            day += timedelta(days=1)
        cur = conn.cursor()
        cur.execute("SELECT MIN(trade_date), MAX(trade_date), COUNT(*) FROM daily_quotes")
        first, latest, count = cur.fetchone()
        print("done", flush=True)
        print("updated trading days:", updated_days, flush=True)
        print("empty days:", empty_days, flush=True)
        print("skipped days:", skipped_days, flush=True)
        print("failed days:", failed_days, flush=True)
        print("inserted total:", total_rows, flush=True)
        if failed_days:
            print("失败日志:", FAILED_FILE, flush=True)
        print("daily_quotes range/count:", compact(first), compact(latest), count, flush=True)
    finally:
        conn.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"执行失败: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
