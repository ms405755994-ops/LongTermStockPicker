#!/usr/bin/env python3
import csv
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path
from urllib import request

API_URL = "http://api.tushare.pro"
FAILED_FILE = Path("logs/backfill_financial_failed.csv")


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


def connect_postgres(env):
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


def fnum(value):
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def ratio(a, b):
    a = fnum(a)
    b = fnum(b)
    if a is None or b in (None, 0.0):
        return None
    return a / b


def score_short_debt_coverage(v):
    if v is None:
        return 60.0
    if v > 2:
        return 100.0
    if v >= 1:
        return 80.0
    if v >= 0.5:
        return 60.0
    return 30.0


def score_interest_debt_ratio(v):
    if v is None:
        return 60.0
    if v < 0.20:
        return 100.0
    if v <= 0.40:
        return 80.0
    if v <= 0.60:
        return 60.0
    return 30.0


def score_cashflow_quality(v):
    if v is None:
        return 60.0
    if v > 1:
        return 100.0
    if v >= 0.5:
        return 80.0
    if v >= 0:
        return 60.0
    return 30.0


def score_debt_to_assets(v):
    if v is None:
        return 60.0
    if v > 1:
        v = v / 100.0
    if v < 0.40:
        return 100.0
    if v <= 0.60:
        return 80.0
    if v <= 0.75:
        return 60.0
    return 30.0


def rows(table):
    fields = table.get("fields") or []
    items = table.get("items") or []
    return [{name: row[i] if i < len(row) else None for i, name in enumerate(fields)} for row in items]


def post_tushare(token, api_name, ts_code, fields, timeout, retry):
    body = json.dumps(
        {"api_name": api_name, "token": token, "params": {"ts_code": ts_code}, "fields": fields},
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
            return rows(payload.get("data") or {})
        except Exception as exc:
            last_error = exc
            if attempt < retry - 1:
                time.sleep(delays[min(attempt, len(delays) - 1)])
    raise last_error


def compute_financial(balance, cashflow, income, indicator):
    b = max(balance, key=lambda r: str(r.get("end_date") or ""), default={})
    ind = max(indicator, key=lambda r: str(r.get("end_date") or ""), default={})
    report_period = max([str(x.get("end_date") or "") for x in balance + cashflow + income + indicator] or [""])

    short_borrow = fnum(b.get("st_borr")) or 0.0
    due_1y = fnum(b.get("non_cur_liab_due_1y")) or 0.0
    short_debt = short_borrow + due_1y
    short_debt_coverage = ratio(b.get("money_cap"), short_debt)

    interest_debt = short_borrow + (fnum(b.get("lt_borr")) or 0.0) + (fnum(b.get("bond_payable")) or 0.0) + due_1y
    interest_debt_ratio = ratio(interest_debt, b.get("total_assets"))

    cash_sum = sum(fnum(r.get("n_cashflow_act")) or 0.0 for r in sorted(cashflow, key=lambda r: str(r.get("end_date") or ""), reverse=True)[:4])
    income_sum = sum(fnum(r.get("n_income")) or 0.0 for r in sorted(income, key=lambda r: str(r.get("end_date") or ""), reverse=True)[:4])
    cashflow_quality = ratio(cash_sum, income_sum)

    debt_to_assets = fnum(ind.get("debt_to_assets"))
    if debt_to_assets is not None and debt_to_assets > 1:
        debt_to_assets = debt_to_assets / 100.0
    if debt_to_assets is None:
        debt_to_assets = ratio(b.get("total_liab"), b.get("total_assets"))

    s1 = score_short_debt_coverage(short_debt_coverage)
    s2 = score_interest_debt_ratio(interest_debt_ratio)
    s3 = score_cashflow_quality(cashflow_quality)
    s4 = score_debt_to_assets(debt_to_assets)
    total = max(0.0, min(100.0, s1 * 0.35 + s2 * 0.25 + s3 * 0.25 + s4 * 0.15))

    missing = []
    if short_debt_coverage is None:
        missing.append("短债覆盖率")
    if interest_debt_ratio is None:
        missing.append("有息负债率")
    if cashflow_quality is None:
        missing.append("经营现金流质量")
    if debt_to_assets is None:
        missing.append("资产负债率")
    note = "基于 Tushare 财务数据计算。" if not missing else "部分财务数据缺失，{}按60分处理。".format("、".join(missing))

    return {
        "report_date": report_period or "unknown",
        "ann_date": str(ind.get("ann_date") or b.get("ann_date") or "") or None,
        "short_debt_coverage": short_debt_coverage,
        "interest_bearing_debt_ratio": interest_debt_ratio,
        "cashflow_quality": cashflow_quality,
        "debt_to_assets": debt_to_assets,
        "financial_safety_score": total,
        "risk_text": note,
        "updated_at": int(time.time() * 1000),
    }


def ensure_table(conn):
    cur = conn.cursor()
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS financial_snapshot(
            ts_code TEXT NOT NULL,
            report_date TEXT NOT NULL,
            ann_date TEXT,
            short_debt_coverage DOUBLE PRECISION,
            interest_bearing_debt_ratio DOUBLE PRECISION,
            cashflow_quality DOUBLE PRECISION,
            debt_to_assets DOUBLE PRECISION,
            financial_safety_score DOUBLE PRECISION,
            risk_text TEXT,
            updated_at BIGINT NOT NULL,
            PRIMARY KEY(ts_code, report_date)
        )
        """
    )
    conn.commit()


def top_codes(conn, limit, missing_only=False):
    cur = conn.cursor()
    if missing_only:
        cur.execute(
            """
            SELECT s.ts_code
            FROM score_result s
            WHERE s.trade_date = (SELECT MAX(trade_date) FROM score_result)
              AND NOT EXISTS (
                  SELECT 1
                  FROM financial_snapshot f
                  WHERE f.ts_code = s.ts_code
              )
            ORDER BY s.total_score DESC
            LIMIT %s
            """,
            (limit,),
        )
    else:
        cur.execute(
            """
            SELECT ts_code
            FROM score_result
            WHERE trade_date = (SELECT MAX(trade_date) FROM score_result)
            ORDER BY total_score DESC
            LIMIT %s
            """,
            (limit,),
        )
    return [r[0] for r in cur.fetchall()]


def upsert_snapshot(conn, ts_code, data):
    cur = conn.cursor()
    cur.execute(
        """
        INSERT INTO financial_snapshot(
            ts_code, report_date, ann_date, short_debt_coverage, interest_bearing_debt_ratio,
            cashflow_quality, debt_to_assets, financial_safety_score, risk_text, updated_at
        ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON CONFLICT(ts_code, report_date) DO UPDATE SET
            ann_date = EXCLUDED.ann_date,
            short_debt_coverage = EXCLUDED.short_debt_coverage,
            interest_bearing_debt_ratio = EXCLUDED.interest_bearing_debt_ratio,
            cashflow_quality = EXCLUDED.cashflow_quality,
            debt_to_assets = EXCLUDED.debt_to_assets,
            financial_safety_score = EXCLUDED.financial_safety_score,
            risk_text = EXCLUDED.risk_text,
            updated_at = EXCLUDED.updated_at
        """,
        (
            ts_code,
            data["report_date"],
            data["ann_date"],
            data["short_debt_coverage"],
            data["interest_bearing_debt_ratio"],
            data["cashflow_quality"],
            data["debt_to_assets"],
            data["financial_safety_score"],
            data["risk_text"],
            data["updated_at"],
        ),
    )
    conn.commit()


def log_failure(ts_code, reason):
    FAILED_FILE.parent.mkdir(parents=True, exist_ok=True)
    exists = FAILED_FILE.exists()
    with FAILED_FILE.open("a", newline="", encoding="utf-8-sig") as f:
        writer = csv.writer(f)
        if not exists:
            writer.writerow(["ts_code", "reason", "failed_at"])
        writer.writerow([ts_code, str(reason)[:500], datetime.now().strftime("%Y-%m-%d %H:%M:%S")])


def main():
    env = load_env()
    token = cfg(env, "TUSHARE_TOKEN")
    if not token:
        raise RuntimeError(".env 缺少 TUSHARE_TOKEN")
    limit = int(cfg(env, "MAX_FINANCIAL_COUNT", "100") or "100")
    timeout = int(cfg(env, "TUSHARE_TIMEOUT", "60") or "60")
    retry = int(cfg(env, "TUSHARE_RETRY", "2") or "2")
    missing_only = cfg(env, "FINANCIAL_MISSING_ONLY", "0") in {"1", "true", "TRUE", "yes", "YES"}
    conn = connect_postgres(env)
    try:
        ensure_table(conn)
        codes = top_codes(conn, limit, missing_only)
        print("PostgreSQL 连接成功: True", flush=True)
        print("待补财务股票数:", len(codes), flush=True)
        ok = 0
        failed = 0
        for i, ts_code in enumerate(codes, 1):
            print(f"processing {i}/{len(codes)} {ts_code}", flush=True)
            try:
                balance = post_tushare(token, "balancesheet", ts_code, "ts_code,ann_date,end_date,money_cap,st_borr,non_cur_liab_due_1y,lt_borr,bond_payable,total_assets,total_liab", timeout, retry)
                cashflow = post_tushare(token, "cashflow", ts_code, "ts_code,end_date,n_cashflow_act", timeout, retry)
                income = post_tushare(token, "income", ts_code, "ts_code,end_date,n_income", timeout, retry)
                indicator = post_tushare(token, "fina_indicator", ts_code, "ts_code,ann_date,end_date,debt_to_assets", timeout, retry)
                data = compute_financial(balance, cashflow, income, indicator)
                if data["report_date"] == "unknown":
                    raise RuntimeError("未获取到财务报告期")
                upsert_snapshot(conn, ts_code, data)
                ok += 1
                print("score:", round(data["financial_safety_score"], 2), "report:", data["report_date"], flush=True)
            except Exception as exc:
                conn.rollback()
                failed += 1
                log_failure(ts_code, exc)
                print("failed:", type(exc).__name__, str(exc)[:120], flush=True)
        print("done", flush=True)
        print("success:", ok, flush=True)
        print("failed:", failed, flush=True)
        if failed:
            print("失败日志:", FAILED_FILE, flush=True)
    finally:
        conn.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"执行失败: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
