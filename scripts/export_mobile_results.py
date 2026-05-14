#!/usr/bin/env python3
import csv
import html
import json
import os
import sys
import zipfile
from datetime import datetime
from pathlib import Path

OUT_DIR = Path("outputs/mobile")
JSON_FILE = OUT_DIR / "latest_score.json"
CSV_FILE = OUT_DIR / "latest_score.csv"
HTML_FILE = OUT_DIR / "report.html"
ZIP_FILE = OUT_DIR / "mobile_result_pack.zip"


def load_env():
    p = Path(".env")
    if not p.exists():
        print("缺少 .env，请在项目根目录创建 .env（可参考 .env.example）", file=sys.stderr)
        return {}
    values = {}
    for raw in p.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        values[k.strip()] = v.strip().strip('"').strip("'")
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


def fetch_latest_results(conn):
    cur = conn.cursor()
    cur.execute("SELECT MAX(trade_date) FROM score_result")
    row = cur.fetchone()
    trade_date = row[0] if row else None
    if not trade_date:
        return None, []
    cur.execute(
        """
        SELECT trade_date, ts_code, name, industry, total_score, signal_level,
               price_position_score, macd_multi_period_score, financial_safety_score,
               financial_report_period, short_debt_coverage, interest_bearing_debt_ratio, cashflow_quality,
               debt_to_assets, financial_risk_note, ownership_score, company_type, ownership_source, ownership_remark,
               price_percentile, distance_to_low, monthly_macd_status, weekly_macd_status, daily_macd_status,
               current_close, ten_year_low, daily_count, weekly_count, monthly_count,
               has_ten_year_data, data_warning, reason
        FROM score_result
        WHERE trade_date = %s
        ORDER BY total_score DESC
        """,
        (trade_date,),
    )
    names = [d[0] for d in cur.description]
    return trade_date, [dict(zip(names, r)) for r in cur.fetchall()]


def convert_row(row):
    return {
        "trade_date": row.get("trade_date"),
        "ts_code": row.get("ts_code"),
        "name": row.get("name"),
        "industry": row.get("industry"),
        "total_score": row.get("total_score") or 0.0,
        "signal_level": row.get("signal_level") or "",
        "price_position_score": row.get("price_position_score") or 0.0,
        "macd_multi_period_score": row.get("macd_multi_period_score") or 0.0,
        "financial_safety_score": row.get("financial_safety_score") or 0.0,
        "financial_report_period": row.get("financial_report_period"),
        "short_debt_coverage": row.get("short_debt_coverage"),
        "interest_bearing_debt_ratio": row.get("interest_bearing_debt_ratio"),
        "cashflow_quality": row.get("cashflow_quality"),
        "debt_to_assets": row.get("debt_to_assets"),
        "financial_risk_note": row.get("financial_risk_note"),
        "ownership_score": row.get("ownership_score") or 0.0,
        "company_type": row.get("company_type") or "未知",
        "ownership_source": row.get("ownership_source") or "default",
        "ownership_remark": row.get("ownership_remark") or "",
        "price_percentile": row.get("price_percentile"),
        "distance_to_low": row.get("distance_to_low"),
        "monthly_macd_status": row.get("monthly_macd_status"),
        "weekly_macd_status": row.get("weekly_macd_status"),
        "daily_macd_status": row.get("daily_macd_status"),
        "current_close": row.get("current_close"),
        "ten_year_low": row.get("ten_year_low"),
        "daily_count": row.get("daily_count"),
        "weekly_count": row.get("weekly_count"),
        "monthly_count": row.get("monthly_count"),
        "has_ten_year_data": bool(row.get("has_ten_year_data")),
        "data_warning": row.get("data_warning") or "",
        "reason": row.get("reason") or "",
    }


def write_outputs(trade_date, results):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    generated_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    payload = {
        "generated_at": generated_at,
        "trade_date": trade_date,
        "model_version": "LongTermStockPicker V3-PC",
        "total_count": len(results),
        "results": [convert_row(r) for r in results],
    }
    JSON_FILE.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    with CSV_FILE.open("w", newline="", encoding="utf-8-sig") as f:
        fields = ["trade_date", "ts_code", "name", "industry", "total_score", "signal_level"]
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for item in payload["results"]:
            writer.writerow({k: item.get(k, "") for k in fields})
    rows = "\n".join(
        f"<tr><td>{html.escape(str(x.get('ts_code','')))}</td><td>{html.escape(str(x.get('name','')))}</td><td>{html.escape(str(x.get('industry','')))}</td><td>{x.get('total_score','')}</td><td>{html.escape(str(x.get('signal_level','')))}</td></tr>"
        for x in payload["results"]
    )
    HTML_FILE.write_text(
        f"<!doctype html><meta charset='utf-8'><title>LongTermStockPicker</title><h1>LongTermStockPicker</h1><p>Generated: {html.escape(generated_at)} ｜ Trade date: {html.escape(str(trade_date))}</p><table border='1' cellspacing='0' cellpadding='6'><tr><th>代码</th><th>名称</th><th>行业</th><th>总分</th><th>等级</th></tr>{rows}</table>",
        encoding="utf-8",
    )
    with zipfile.ZipFile(ZIP_FILE, "w", compression=zipfile.ZIP_DEFLATED) as z:
        z.write(JSON_FILE, "latest_score.json")
        z.write(CSV_FILE, "latest_score.csv")
        z.write(HTML_FILE, "report.html")


def main():
    env = load_env()
    if not env:
        sys.exit(1)
    if cfg(env, "DB_TYPE", "postgresql").lower() != "postgresql":
        print("export_mobile_results.py 当前只从 PostgreSQL 导出。请设置 DB_TYPE=postgresql。", file=sys.stderr)
        sys.exit(1)
    try:
        conn = connect_postgres(env)
        trade_date, results = fetch_latest_results(conn)
        conn.close()
    except Exception as exc:
        print(f"读取 PostgreSQL 失败: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(2)
    if not results:
        print("电脑端 score_result 为空，请先运行 cloud_scan.py 或模型评分脚本。")
        return
    write_outputs(trade_date, results)
    print(f"已导出 {len(results)} 条结果")
    print(f"JSON: {JSON_FILE}")
    print(f"CSV: {CSV_FILE}")
    print(f"HTML: {HTML_FILE}")
    print(f"ZIP: {ZIP_FILE}")


if __name__ == "__main__":
    main()
