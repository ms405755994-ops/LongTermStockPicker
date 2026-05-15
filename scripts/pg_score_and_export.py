#!/usr/bin/env python3
import csv
import html
import json
import math
import os
import sys
import time
import zipfile
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path

MODEL_VERSION = "LongTermStockPicker PG-V1"
OUT_DIR = Path("outputs/mobile")
JSON_FILE = OUT_DIR / "latest_score.json"
CSV_FILE = OUT_DIR / "latest_score.csv"
HTML_FILE = OUT_DIR / "report.html"
ZIP_FILE = OUT_DIR / "mobile_result_pack.zip"
OWNERSHIP_CSV = Path("app/src/main/assets/company_ownership.csv")


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


def fnum(value):
    if value is None or value == "":
        return None
    try:
        v = float(value)
    except (TypeError, ValueError):
        return None
    return v if math.isfinite(v) else None


def clamp(v, lo=0.0, hi=100.0):
    return max(lo, min(hi, v))


def parse_yyyymmdd(value):
    if isinstance(value, date):
        return value
    s = str(value or "").strip()
    if len(s) >= 10 and s[4] == "-" and s[7] == "-":
        return datetime.strptime(s[:10], "%Y-%m-%d").date()
    if len(s) != 8 or not s.isdigit():
        return None
    return datetime.strptime(s, "%Y%m%d").date()


def compact_date(value):
    d = parse_yyyymmdd(value)
    return d.strftime("%Y%m%d") if d else str(value or "").strip()


def years_before(date_text, years):
    d = parse_yyyymmdd(date_text)
    if not d:
        return None
    try:
        return d.replace(year=d.year - years).strftime("%Y%m%d")
    except ValueError:
        return d.replace(year=d.year - years, day=28).strftime("%Y%m%d")


def has_years(first_date, latest_date, years):
    first = parse_yyyymmdd(first_date)
    latest = parse_yyyymmdd(latest_date)
    if not first or not latest:
        return False
    try:
        threshold = latest.replace(year=latest.year - years)
    except ValueError:
        threshold = latest.replace(year=latest.year - years, day=28)
    # The exact calendar threshold can fall on a weekend/holiday. Treat the
    # first trading day within the following week as satisfying the window.
    return first <= threshold + timedelta(days=7)


class PgSchema:
    def __init__(self, conn):
        self.conn = conn
        self.tables = self._load_tables()

    def _load_tables(self):
        cur = self.conn.cursor()
        cur.execute(
            """
            SELECT table_name, column_name
            FROM information_schema.columns
            WHERE table_schema = 'public'
            ORDER BY table_name, ordinal_position
            """
        )
        tables = {}
        for table, column in cur.fetchall():
            tables.setdefault(table, []).append(column)
        return tables

    def has_table(self, name):
        return name in self.tables

    def columns(self, name):
        return self.tables.get(name, [])

    def pick(self, table, *names):
        cols = self.columns(table)
        lower = {c.lower(): c for c in cols}
        for name in names:
            found = lower.get(name.lower())
            if found:
                return found
        return None


def qident(name):
    return '"' + name.replace('"', '""') + '"'


def create_score_table(conn):
    cur = conn.cursor()
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS score_result(
            trade_date TEXT NOT NULL,
            ts_code TEXT NOT NULL,
            name TEXT,
            industry TEXT,
            total_score DOUBLE PRECISION NOT NULL,
            signal_level TEXT NOT NULL,
            price_position_score DOUBLE PRECISION NOT NULL,
            macd_multi_period_score DOUBLE PRECISION NOT NULL,
            financial_safety_score DOUBLE PRECISION NOT NULL,
            financial_report_period TEXT,
            short_debt_coverage DOUBLE PRECISION,
            interest_bearing_debt_ratio DOUBLE PRECISION,
            cashflow_quality DOUBLE PRECISION,
            debt_to_assets DOUBLE PRECISION,
            financial_risk_note TEXT,
            ownership_score DOUBLE PRECISION NOT NULL,
            company_type TEXT,
            ownership_source TEXT,
            ownership_remark TEXT,
            price_percentile DOUBLE PRECISION,
            distance_to_low DOUBLE PRECISION,
            monthly_macd_status TEXT,
            weekly_macd_status TEXT,
            daily_macd_status TEXT,
            current_close DOUBLE PRECISION,
            ten_year_low DOUBLE PRECISION,
            daily_count INTEGER,
            weekly_count INTEGER,
            monthly_count INTEGER,
            has_ten_year_data BOOLEAN NOT NULL,
            data_warning TEXT,
            reason TEXT,
            updated_at BIGINT NOT NULL,
            PRIMARY KEY(trade_date, ts_code)
        )
        """
    )
    cur.execute("CREATE INDEX IF NOT EXISTS idx_score_result_trade_date ON score_result(trade_date)")
    cur.execute("CREATE INDEX IF NOT EXISTS idx_score_result_total_score ON score_result(total_score)")
    for column, ddl in [
        ("current_close", "DOUBLE PRECISION"),
        ("financial_report_period", "TEXT"),
        ("short_debt_coverage", "DOUBLE PRECISION"),
        ("interest_bearing_debt_ratio", "DOUBLE PRECISION"),
        ("cashflow_quality", "DOUBLE PRECISION"),
        ("debt_to_assets", "DOUBLE PRECISION"),
        ("financial_risk_note", "TEXT"),
        ("company_type", "TEXT"),
        ("ownership_source", "TEXT"),
        ("ownership_remark", "TEXT"),
        ("ten_year_low", "DOUBLE PRECISION"),
        ("daily_count", "INTEGER"),
        ("weekly_count", "INTEGER"),
        ("monthly_count", "INTEGER"),
        ("has_ten_year_data", "BOOLEAN DEFAULT FALSE"),
        ("data_warning", "TEXT"),
        ("reason", "TEXT"),
        ("updated_at", "BIGINT DEFAULT 0"),
    ]:
        cur.execute(f"ALTER TABLE score_result ADD COLUMN IF NOT EXISTS {column} {ddl}")
    conn.commit()


def load_stock_basic(conn, schema, max_count):
    table = "stock_basic"
    if not schema.has_table(table):
        raise RuntimeError("未找到 stock_basic 表")
    ts_col = schema.pick(table, "ts_code", "symbol", "tsCode")
    name_col = schema.pick(table, "name", "stock_name", "stockName")
    industry_col = schema.pick(table, "industry")
    list_col = schema.pick(table, "list_date", "listDate")
    market_col = schema.pick(table, "market")
    if not ts_col:
        raise RuntimeError("stock_basic 缺少 ts_code/symbol 字段")
    fields = [ts_col]
    optional = [name_col, industry_col, list_col, market_col]
    for col in optional:
        if col:
            fields.append(col)
    cur = conn.cursor()
    cur.execute(f"SELECT {', '.join(qident(c) for c in fields)} FROM {qident(table)} ORDER BY {qident(ts_col)} LIMIT %s", (max_count,))
    out = []
    for row in cur.fetchall():
        data = dict(zip(fields, row))
        ts_code = str(data.get(ts_col) or "").strip().upper()
        if not ts_code:
            continue
        name = str(data.get(name_col) or "").strip() if name_col else ""
        out.append(
            {
                "ts_code": ts_code,
                "name": name,
                "industry": str(data.get(industry_col) or "").strip() if industry_col else "",
                "list_date": compact_date(data.get(list_col)) if list_col else "",
                "market": str(data.get(market_col) or "").strip() if market_col else "",
                "is_st": "ST" in name.upper(),
            }
        )
    return out


def daily_date_range(conn, schema):
    table = "daily_quotes"
    if not schema.has_table(table):
        raise RuntimeError("未找到 daily_quotes 表")
    date_col = schema.pick(table, "trade_date", "tradeDate")
    if not date_col:
        raise RuntimeError("daily_quotes 缺少 trade_date 字段")
    cur = conn.cursor()
    cur.execute(f"SELECT MIN({qident(date_col)}), MAX({qident(date_col)}) FROM {qident(table)}")
    first, latest = cur.fetchone()
    return compact_date(first) if first else None, compact_date(latest) if latest else None


def load_dailies(conn, schema, ts_code, start_date, end_date):
    table = "daily_quotes"
    ts_col = schema.pick(table, "ts_code", "symbol", "tsCode")
    date_col = schema.pick(table, "trade_date", "tradeDate")
    close_col = schema.pick(table, "close")
    high_col = schema.pick(table, "high")
    low_col = schema.pick(table, "low")
    open_col = schema.pick(table, "open")
    amount_col = schema.pick(table, "amount")
    vol_col = schema.pick(table, "vol", "volume")
    adj_col = schema.pick(table, "adj_type", "adjType")
    if not ts_col or not date_col or not close_col:
        raise RuntimeError("daily_quotes 缺少 ts_code/symbol、trade_date 或 close 字段")
    fields = [ts_col, date_col, close_col]
    for col in [open_col, high_col, low_col, amount_col, vol_col]:
        if col and col not in fields:
            fields.append(col)
    where = [f"{qident(ts_col)} = %s", f"{qident(date_col)} >= %s", f"{qident(date_col)} <= %s"]
    params = [ts_code, parse_yyyymmdd(start_date) or start_date, parse_yyyymmdd(end_date) or end_date]
    if adj_col:
        cur = conn.cursor()
        cur.execute(
            f"SELECT COUNT(*) FROM {qident(table)} WHERE {qident(ts_col)} = %s AND lower({qident(adj_col)}::text) = 'qfq'",
            (ts_code,),
        )
        if cur.fetchone()[0] > 0:
            where.append(f"lower({qident(adj_col)}::text) = 'qfq'")
    sql = f"SELECT {', '.join(qident(c) for c in fields)} FROM {qident(table)} WHERE {' AND '.join(where)} ORDER BY {qident(date_col)}"
    cur = conn.cursor()
    cur.execute(sql, params)
    rows = []
    for row in cur.fetchall():
        data = dict(zip(fields, row))
        close = fnum(data.get(close_col))
        if close is None:
            continue
        rows.append(
            {
                "trade_date": compact_date(data.get(date_col)),
                "close": close,
                "open": fnum(data.get(open_col)) if open_col else None,
                "high": fnum(data.get(high_col)) if high_col else None,
                "low": fnum(data.get(low_col)) if low_col else None,
                "amount": fnum(data.get(amount_col)) if amount_col else None,
                "vol": fnum(data.get(vol_col)) if vol_col else None,
            }
        )
    return apply_qfq_adjustment(conn, schema, ts_code, rows, end_date)


def apply_qfq_adjustment(conn, schema, ts_code, rows, end_date):
    if not rows or not schema.has_table("adj_factors"):
        return rows
    cur = conn.cursor()
    cur.execute(
        """
        SELECT trade_date, adj_factor
        FROM adj_factors
        WHERE ts_code = %s
          AND trade_date <= %s
        ORDER BY trade_date
        """,
        (ts_code, parse_yyyymmdd(end_date) or end_date),
    )
    factors = [(compact_date(d), fnum(v)) for d, v in cur.fetchall() if fnum(v) is not None and fnum(v) > 0]
    if not factors:
        return rows
    latest_factor = factors[-1][1]
    factor_by_date = {d: v for d, v in factors}
    factor_dates = [d for d, _ in factors]
    factor_values = [v for _, v in factors]
    idx = 0
    current_factor = None
    adjusted = []
    for row in rows:
        trade_date = row["trade_date"]
        while idx < len(factor_dates) and factor_dates[idx] <= trade_date:
            current_factor = factor_values[idx]
            idx += 1
        factor = factor_by_date.get(trade_date) or current_factor
        if not factor or not latest_factor:
            adjusted.append(row)
            continue
        ratio = factor / latest_factor
        adjusted_row = dict(row)
        for key in ["open", "high", "low", "close"]:
            if adjusted_row.get(key) is not None:
                adjusted_row[key] = adjusted_row[key] * ratio
        adjusted.append(adjusted_row)
    return adjusted


def load_ownership():
    if not OWNERSHIP_CSV.exists():
        return {}
    out = {}
    with OWNERSHIP_CSV.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            ts_code = str(row.get("ts_code") or "").strip().upper()
            if not ts_code:
                continue
            out[ts_code] = {
                "company_type": str(row.get("company_type") or "未知").strip() or "未知",
                "score": clamp(fnum(row.get("ownership_score")) or 60.0),
                "source": str(row.get("source") or "manual").strip() or "manual",
                "remark": str(row.get("remark") or "").strip(),
            }
    return out


def latest_financial_snapshot(conn, schema, ts_code):
    if not schema.has_table("financial_snapshot"):
        return None
    table = "financial_snapshot"
    ts_col = schema.pick(table, "ts_code", "tsCode", "symbol")
    report_col = schema.pick(table, "report_date", "reportDate", "end_date", "endDate")
    score_col = schema.pick(table, "financial_safety_score", "financialSafetyScore")
    risk_col = schema.pick(table, "risk_text", "riskText")
    short_col = schema.pick(table, "short_debt_coverage", "shortDebtCoverage")
    interest_col = schema.pick(table, "interest_bearing_debt_ratio", "interestBearingDebtRatio")
    cashflow_col = schema.pick(table, "cashflow_quality", "cashflowQuality")
    debt_col = schema.pick(table, "debt_to_assets", "debtToAssets")
    if not ts_col or not report_col:
        return None
    select_cols = [report_col]
    for col in [score_col, risk_col, short_col, interest_col, cashflow_col, debt_col]:
        if col:
            select_cols.append(col)
    cur = conn.cursor()
    cur.execute(
        f"SELECT {', '.join(qident(c) for c in select_cols)} FROM {qident(table)} WHERE {qident(ts_col)} = %s ORDER BY {qident(report_col)} DESC LIMIT 1",
        (ts_code,),
    )
    row = cur.fetchone()
    if not row:
        return None
    data = dict(zip(select_cols, row))
    score = fnum(data.get(score_col)) if score_col else None
    return {
        "score": clamp(score if score is not None else 60.0),
        "note": str(data.get(risk_col) or "基于本地财务快照").strip() if risk_col else "基于本地财务快照",
        "report_period": compact_date(data.get(report_col)),
        "short_debt_coverage": fnum(data.get(short_col)) if short_col else None,
        "interest_bearing_debt_ratio": fnum(data.get(interest_col)) if interest_col else None,
        "cashflow_quality": fnum(data.get(cashflow_col)) if cashflow_col else None,
        "debt_to_assets": fnum(data.get(debt_col)) if debt_col else None,
    }


def financial_score(conn, schema, ts_code):
    snap = latest_financial_snapshot(conn, schema, ts_code)
    if snap:
        return snap
    return {
        "score": 60.0,
        "note": "财务数据缺失，暂用默认分",
        "report_period": None,
        "short_debt_coverage": None,
        "interest_bearing_debt_ratio": None,
        "cashflow_quality": None,
        "debt_to_assets": None,
    }


def ema(values, period):
    if not values:
        return []
    k = 2.0 / (period + 1)
    cur = values[0]
    out = [cur]
    for value in values[1:]:
        cur = value * k + cur * (1 - k)
        out.append(cur)
    return out


def macd_status(closes):
    if len(closes) < 3:
        return "震荡"
    ema12 = ema(closes, 12)
    ema26 = ema(closes, 26)
    dif = [a - b for a, b in zip(ema12, ema26)]
    dea = ema(dif, 9)
    bar = [(d - e) * 2.0 for d, e in zip(dif, dea)]
    if len(bar) < 3:
        return "震荡"
    b0, b1, b2 = bar[-3], bar[-2], bar[-1]
    if dif[-2] <= dea[-2] and dif[-1] > dea[-1]:
        return "金叉"
    if dif[-2] >= dea[-2] and dif[-1] < dea[-1]:
        return "死叉"
    if b2 < 0 and b0 < 0 and b1 < 0:
        if abs(b0) > abs(b1) > abs(b2):
            return "绿柱缩短"
        if abs(b0) < abs(b1) < abs(b2):
            return "绿柱放大"
    if b2 > 0 and b0 > 0 and b1 > 0:
        if b0 < b1 < b2:
            return "红柱放大"
        return "红柱缩短/减弱"
    return "震荡"


def macd_score(status):
    return {
        "绿柱缩短": 75.0,
        "金叉": 90.0,
        "红柱放大": 85.0,
        "红柱缩短/减弱": 65.0,
        "绿柱放大": 30.0,
        "死叉": 20.0,
        "震荡": 50.0,
    }.get(status, 50.0)


def resample_last_close(dailies, key_len):
    buckets = {}
    for row in dailies:
        d = str(row["trade_date"])
        if len(d) >= key_len:
            buckets[d[:key_len]] = row["close"]
    return [v for _, v in sorted(buckets.items())]


def resample_weekly_last_close(dailies):
    buckets = {}
    for row in dailies:
        d = parse_yyyymmdd(row["trade_date"])
        if d:
            iso = d.isocalendar()
            buckets[f"{iso.year:04d}{iso.week:02d}"] = row["close"]
    return [v for _, v in sorted(buckets.items())]


def price_position_score(closes, current_close, lows=None):
    sorted_closes = sorted(closes)
    count_le = sum(1 for v in sorted_closes if v <= current_close)
    percentile = count_le / len(sorted_closes)
    low_candidates = [v for v in (lows or []) if v is not None and v > 0]
    low = min(low_candidates) if low_candidates else min(sorted_closes)
    distance = current_close / low - 1 if low > 0 else None
    if percentile <= 0.10:
        score = 100.0
    elif percentile <= 0.20:
        score = 85.0
    elif percentile <= 0.30:
        score = 70.0
    elif percentile <= 0.40:
        score = 55.0
    else:
        score = 30.0
    if distance is not None:
        if distance <= 0.20:
            score += 10.0
        elif distance <= 0.40:
            score += 5.0
        elif distance > 1.00:
            score -= 10.0
    return clamp(score), percentile, distance, low


def signal_level(total):
    if total >= 85:
        return "核心观察"
    if total >= 75:
        return "优先观察"
    if total >= 65:
        return "低位潜伏"
    if total >= 55:
        return "只观察"
    return "剔除/不关注"


def build_reason(price_score, monthly, weekly, daily, financial_score_value, financial_note, data_warning):
    parts = []
    if price_score >= 70:
        parts.append("当前价格处于统计窗口内的相对低位区")
    elif price_score >= 55:
        parts.append("价格位置中等偏低")
    macd_bits = []
    if monthly in ("绿柱缩短", "金叉"):
        macd_bits.append(f"月线{monthly}")
    if weekly in ("绿柱缩短", "金叉"):
        macd_bits.append(f"周线{weekly}")
    if daily in ("金叉", "红柱放大"):
        macd_bits.append(f"日线{daily}")
    if macd_bits:
        parts.append("，".join(macd_bits) + "，具备一定底部修复/趋势特征（模型简化判断）")
    parts.append("财务风险正常" if financial_score_value >= 55 else "财务风险分偏低")
    if financial_note:
        parts.append(financial_note)
    if data_warning:
        parts.append(data_warning)
    return "；".join(parts) + "。"


def score_stock(conn, schema, basic, ownership, start_date, end_date):
    ts_code = basic["ts_code"]
    if basic["is_st"]:
        return None, "ST 风险"
    list_date = basic.get("list_date")
    if list_date and parse_yyyymmdd(list_date) and not has_years(list_date, end_date, 5):
        return None, "上市不足5年"
    dailies = load_dailies(conn, schema, ts_code, start_date, end_date)
    if not dailies:
        return None, "无日线数据"
    first_date = dailies[0]["trade_date"]
    latest_date = dailies[-1]["trade_date"]
    if not has_years(first_date, latest_date, 5):
        return None, "历史数据不足5年"
    closes = [row["close"] for row in dailies if row["close"] is not None]
    if len(closes) < 500:
        return None, "日线少于500条"
    current_close = closes[-1]
    lows = [row["low"] for row in dailies if row.get("low") is not None]
    price_score, percentile, distance, low = price_position_score(closes, current_close, lows)
    weekly = resample_weekly_last_close(dailies)
    monthly = resample_last_close(dailies, 6)
    daily_status = macd_status(closes)
    weekly_status = macd_status(weekly)
    monthly_status = macd_status(monthly)
    macd_multi = macd_score(monthly_status) * 0.45 + macd_score(weekly_status) * 0.35 + macd_score(daily_status) * 0.20
    fin = financial_score(conn, schema, ts_code)
    fin_score = fin["score"]
    fin_note = fin["note"]
    own = ownership.get(ts_code, {"company_type": "未知", "score": 60.0, "source": "default", "remark": "CSV未配置，使用默认分"})
    ownership_score = own["score"]
    total = price_score * 0.30 + macd_multi * 0.35 + fin_score * 0.25 + ownership_score * 0.10
    has_ten = has_years(first_date, latest_date, 10)
    data_warning = "" if has_ten else "历史数据不足10年，评分可信度下降"
    return {
        "trade_date": latest_date,
        "ts_code": ts_code,
        "name": basic.get("name") or "",
        "industry": basic.get("industry") or "",
        "total_score": round(total, 4),
        "signal_level": signal_level(total),
        "price_position_score": round(price_score, 4),
        "macd_multi_period_score": round(macd_multi, 4),
        "financial_safety_score": round(fin_score, 4),
        "financial_report_period": fin.get("report_period"),
        "short_debt_coverage": fin.get("short_debt_coverage"),
        "interest_bearing_debt_ratio": fin.get("interest_bearing_debt_ratio"),
        "cashflow_quality": fin.get("cashflow_quality"),
        "debt_to_assets": fin.get("debt_to_assets"),
        "financial_risk_note": fin_note,
        "ownership_score": round(ownership_score, 4),
        "company_type": own.get("company_type") or "未知",
        "ownership_source": own.get("source") or "default",
        "ownership_remark": own.get("remark") or "",
        "price_percentile": round(percentile, 6),
        "distance_to_low": round(distance, 6) if distance is not None else None,
        "monthly_macd_status": monthly_status,
        "weekly_macd_status": weekly_status,
        "daily_macd_status": daily_status,
        "current_close": round(current_close, 4),
        "ten_year_low": round(low, 4),
        "daily_count": len(dailies),
        "weekly_count": len(weekly),
        "monthly_count": len(monthly),
        "has_ten_year_data": has_ten,
        "data_warning": data_warning,
        "reason": build_reason(price_score, monthly_status, weekly_status, daily_status, fin_score, fin_note, data_warning),
        "updated_at": int(time.time() * 1000),
    }, None


def upsert_scores(conn, rows):
    if not rows:
        return 0
    cur = conn.cursor()
    sql = """
        INSERT INTO score_result(
            trade_date, ts_code, name, industry, total_score, signal_level,
            price_position_score, macd_multi_period_score, financial_safety_score, ownership_score,
            financial_report_period, short_debt_coverage, interest_bearing_debt_ratio, cashflow_quality,
            debt_to_assets, financial_risk_note, company_type, ownership_source, ownership_remark,
            price_percentile, distance_to_low, monthly_macd_status, weekly_macd_status, daily_macd_status,
            current_close, ten_year_low, daily_count, weekly_count, monthly_count,
            has_ten_year_data, data_warning, reason, updated_at
        ) VALUES (
            %(trade_date)s, %(ts_code)s, %(name)s, %(industry)s, %(total_score)s, %(signal_level)s,
            %(price_position_score)s, %(macd_multi_period_score)s, %(financial_safety_score)s, %(ownership_score)s,
            %(financial_report_period)s, %(short_debt_coverage)s, %(interest_bearing_debt_ratio)s, %(cashflow_quality)s,
            %(debt_to_assets)s, %(financial_risk_note)s, %(company_type)s, %(ownership_source)s, %(ownership_remark)s,
            %(price_percentile)s, %(distance_to_low)s, %(monthly_macd_status)s, %(weekly_macd_status)s, %(daily_macd_status)s,
            %(current_close)s, %(ten_year_low)s, %(daily_count)s, %(weekly_count)s, %(monthly_count)s,
            %(has_ten_year_data)s, %(data_warning)s, %(reason)s, %(updated_at)s
        )
        ON CONFLICT(trade_date, ts_code) DO UPDATE SET
            name = EXCLUDED.name,
            industry = EXCLUDED.industry,
            total_score = EXCLUDED.total_score,
            signal_level = EXCLUDED.signal_level,
            price_position_score = EXCLUDED.price_position_score,
            macd_multi_period_score = EXCLUDED.macd_multi_period_score,
            financial_safety_score = EXCLUDED.financial_safety_score,
            ownership_score = EXCLUDED.ownership_score,
            financial_report_period = EXCLUDED.financial_report_period,
            short_debt_coverage = EXCLUDED.short_debt_coverage,
            interest_bearing_debt_ratio = EXCLUDED.interest_bearing_debt_ratio,
            cashflow_quality = EXCLUDED.cashflow_quality,
            debt_to_assets = EXCLUDED.debt_to_assets,
            financial_risk_note = EXCLUDED.financial_risk_note,
            company_type = EXCLUDED.company_type,
            ownership_source = EXCLUDED.ownership_source,
            ownership_remark = EXCLUDED.ownership_remark,
            price_percentile = EXCLUDED.price_percentile,
            distance_to_low = EXCLUDED.distance_to_low,
            monthly_macd_status = EXCLUDED.monthly_macd_status,
            weekly_macd_status = EXCLUDED.weekly_macd_status,
            daily_macd_status = EXCLUDED.daily_macd_status,
            current_close = EXCLUDED.current_close,
            ten_year_low = EXCLUDED.ten_year_low,
            daily_count = EXCLUDED.daily_count,
            weekly_count = EXCLUDED.weekly_count,
            monthly_count = EXCLUDED.monthly_count,
            has_ten_year_data = EXCLUDED.has_ten_year_data,
            data_warning = EXCLUDED.data_warning,
            reason = EXCLUDED.reason,
            updated_at = EXCLUDED.updated_at
    """
    cur.executemany(sql, rows)
    conn.commit()
    return len(rows)


def write_outputs(trade_date, results):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    results = sorted(results, key=lambda row: row["total_score"], reverse=True)
    generated_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    payload = {
        "generated_at": generated_at,
        "trade_date": trade_date,
        "model_version": MODEL_VERSION,
        "total_count": len(results),
        "results": [
            {k: v for k, v in row.items() if k != "updated_at"}
            for row in results
        ],
    }
    JSON_FILE.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    fields = [
        "trade_date", "ts_code", "name", "industry", "total_score", "signal_level",
        "price_position_score", "macd_multi_period_score", "financial_safety_score", "ownership_score",
        "financial_report_period", "financial_risk_note", "company_type", "ownership_source", "ownership_remark",
        "current_close", "ten_year_low", "has_ten_year_data", "data_warning", "reason",
    ]
    with CSV_FILE.open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for row in payload["results"]:
            writer.writerow({field: row.get(field, "") for field in fields})
    table_rows = "\n".join(
        "<tr>"
        f"<td>{html.escape(str(r.get('ts_code', '')))}</td>"
        f"<td>{html.escape(str(r.get('name', '')))}</td>"
        f"<td>{html.escape(str(r.get('industry', '')))}</td>"
        f"<td>{r.get('total_score', '')}</td>"
        f"<td>{html.escape(str(r.get('signal_level', '')))}</td>"
        "</tr>"
        for r in payload["results"]
    )
    HTML_FILE.write_text(
        "<!doctype html><meta charset='utf-8'>"
        f"<title>{MODEL_VERSION}</title><h1>{MODEL_VERSION}</h1>"
        f"<p>Generated: {html.escape(generated_at)} ｜ Trade date: {html.escape(str(trade_date))} ｜ Count: {len(results)}</p>"
        "<table border='1' cellspacing='0' cellpadding='6'>"
        "<tr><th>代码</th><th>名称</th><th>行业</th><th>总分</th><th>等级</th></tr>"
        f"{table_rows}</table>",
        encoding="utf-8",
    )
    with zipfile.ZipFile(ZIP_FILE, "w", compression=zipfile.ZIP_DEFLATED) as z:
        z.write(JSON_FILE, "latest_score.json")
        z.write(CSV_FILE, "latest_score.csv")
        z.write(HTML_FILE, "report.html")


def main():
    env = load_env()
    max_count = int(cfg(env, "MAX_SCAN_COUNT", "100") or "100")
    end_date_override = cfg(env, "END_DATE", "")
    conn = connect_postgres(env)
    try:
        create_score_table(conn)
        schema = PgSchema(conn)
        found = [name for name in ["stock_basic", "daily_quotes", "financial_snapshot", "balance_sheet", "cashflow", "income", "fina_indicator"] if schema.has_table(name)]
        first_daily, latest_daily = daily_date_range(conn, schema)
        end_date = end_date_override or latest_daily
        if not end_date:
            raise RuntimeError("daily_quotes 没有可用 trade_date")
        start_date = years_before(end_date, 10)
        if not start_date:
            raise RuntimeError("无法计算 10 年窗口 startDate")
        ownership = load_ownership()
        basics = load_stock_basic(conn, schema, max_count)
        results = []
        skipped = []
        for basic in basics:
            result, reason = score_stock(conn, schema, basic, ownership, start_date, end_date)
            if result:
                results.append(result)
            else:
                skipped.append((basic["ts_code"], reason))
        written = upsert_scores(conn, results)
        write_outputs(end_date, results)
        print("PostgreSQL 连接成功: True")
        print("识别到的表:", ", ".join(found) if found else "无")
        print("daily_quotes 最早交易日:", first_daily or "—")
        print("daily_quotes 最新交易日:", latest_daily or "—")
        print("本次评分窗口:", f"{start_date} - {end_date}")
        print("是否满足至少10年数据:", bool(first_daily and start_date and str(first_daily) <= start_date))
        print("读取 stock_basic 数量:", len(basics))
        print("score_result 写入数量:", written)
        print("跳过数量:", len(skipped))
        print("mobile_result_pack.zip 是否生成:", ZIP_FILE.exists())
        print("mobile_result_pack.zip 路径:", ZIP_FILE)
    finally:
        conn.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"执行失败: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
