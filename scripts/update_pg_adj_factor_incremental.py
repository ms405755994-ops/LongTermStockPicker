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
FIELDS = "ts_code,trade_date,adj_factor"
FAILED_FILE = Path("logs/update_pg_adj_factor_failed.csv")


def load_env():
    path = Path(".env")
    if not path.exists():
        raise RuntimeError("缺少 .env，请在项目根目录创建 .env")
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


def ensure_table(conn):
    cur = conn.cursor()
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS adj_factors(
            ts_code VARCHAR NOT NULL,
            trade_date DATE NOT NULL,
            adj_factor NUMERIC,
            PRIMARY KEY(ts_code, trade_date)
        )
        """
    )
    cur.execute("CREATE INDEX IF NOT EXISTS idx_adj_factors_trade_date ON adj_factors(trade_date)")
    conn.commit()


def existing_count(conn, trade_date):
    cur = conn.cursor()
    cur.execute("SELECT COUNT(*) FROM adj_factors WHERE trade_date = %s", (parse_date(trade_date) or trade_date,))
    return int(cur.fetchone()[0])


def post_tushare(token, trade_date, timeout, retry):
    body = json.dumps(
        {
            "api_name": "adj_factor",
            "token": token,
            "params": {"trade_date": trade_date},
            "fields": FIELDS,
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


def upsert_rows(conn, rows):
    if not rows:
        return 0
    values = []
    for row in rows:
        ts_code = str(row.get("ts_code") or "").strip().upper()
        trade_date = parse_date(row.get("trade_date"))
        adj_factor = fnum(row.get("adj_factor"))
        if ts_code and trade_date and adj_factor is not None:
            values.append((ts_code, trade_date, adj_factor))
    if not values:
        return 0
    cur = conn.cursor()
    cur.executemany(
        """
        INSERT INTO adj_factors(ts_code, trade_date, adj_factor)
        VALUES(%s, %s, %s)
        ON CONFLICT(ts_code, trade_date) DO UPDATE SET
            adj_factor = EXCLUDED.adj_factor
        """,
        values,
    )
    conn.commit()
    return len(values)


def log_failure(trade_date, reason):
    FAILED_FILE.parent.mkdir(parents=True, exist_ok=True)
    exists = FAILED_FILE.exists()
    with FAILED_FILE.open("a", newline="", encoding="utf-8-sig") as f:
        writer = csv.writer(f)
        if not exists:
            writer.writerow(["trade_date", "reason", "failed_at"])
        writer.writerow([trade_date, str(reason)[:500], datetime.now().strftime("%Y-%m-%d %H:%M:%S")])


def main():
    env = load_env()
    token = cfg(env, "TUSHARE_TOKEN")
    if not token:
        raise RuntimeError(".env 缺少 TUSHARE_TOKEN，无法更新 adj_factors")
    start = parse_date(cfg(env, "START_DATE", "20160509"))
    end = parse_date(cfg(env, "END_DATE", date.today().strftime("%Y%m%d")))
    if not start or not end:
        raise RuntimeError("START_DATE/END_DATE 必须是 yyyyMMdd 或 yyyy-MM-dd")
    if start > end:
        print("无需更新：START_DATE 晚于 END_DATE")
        return
    force = cfg(env, "FORCE", "0") == "1"
    timeout = int(cfg(env, "TUSHARE_TIMEOUT", "180") or "180")
    retry = int(cfg(env, "TUSHARE_RETRY", "3") or "3")
    conn = connect_postgres(env)
    try:
        ensure_table(conn)
        total_days = (end - start).days + 1
        total_rows = 0
        updated_days = 0
        empty_days = 0
        skipped_days = 0
        failed_days = 0
        print("PostgreSQL 连接成功: True", flush=True)
        print("复权因子更新范围:", start.strftime("%Y%m%d"), "-", end.strftime("%Y%m%d"), flush=True)
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
                rows = post_tushare(token, trade_date, timeout=timeout, retry=retry)
                inserted = upsert_rows(conn, rows)
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
        cur.execute("SELECT MIN(trade_date), MAX(trade_date), COUNT(*) FROM adj_factors")
        first, latest, count = cur.fetchone()
        print("done", flush=True)
        print("updated trading days:", updated_days, flush=True)
        print("empty days:", empty_days, flush=True)
        print("skipped days:", skipped_days, flush=True)
        print("failed days:", failed_days, flush=True)
        print("inserted total:", total_rows, flush=True)
        if failed_days:
            print("失败日志:", FAILED_FILE, flush=True)
        print("adj_factors range/count:", compact(first), compact(latest), count, flush=True)
    finally:
        conn.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"执行失败: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
