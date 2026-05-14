#!/usr/bin/env python3
import csv
import html
import json
import math
import os
import sqlite3
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib import request

API_URL = "http://api.tushare.pro"
SQLITE_PATH = Path("data/longterm_stock_picker.db")
PROGRESS_FILE = Path("data/cloud_scan_progress.json")
FAILED_FILE = Path("logs/cloud_scan_failed.csv")
LATEST_JSON = Path("docs/results/latest.json")
OUT_CSV = Path("outputs/longterm_score_latest.csv")
OUT_HTML = Path("outputs/longterm_score_report.html")
DAILY_FIELDS = "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount"


def load_env_file():
    env_path = Path(".env")
    if not env_path.exists():
        print("缺少 .env，请在项目根目录创建 .env（可参考 .env.example）", file=sys.stderr)
        return {}
    values = {}
    for raw in env_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def cfg(env, key, default=""):
    return os.environ.get(key, env.get(key, default)).strip()


class ScanDatabase:
    def __init__(self, env):
        self.db_type = cfg(env, "DB_TYPE", "postgresql").lower() or "postgresql"
        if self.db_type == "postgresql":
            try:
                import psycopg2
            except ImportError as exc:
                raise RuntimeError("缺少 psycopg2，无法连接 PostgreSQL。请安装 psycopg2-binary，或在 .env 设置 DB_TYPE=sqlite") from exc
            self.conn = psycopg2.connect(
                host=cfg(env, "DB_HOST", "172.23.112.1"),
                port=int(cfg(env, "DB_PORT", "5432")),
                user=cfg(env, "DB_USER", "postgres"),
                password=cfg(env, "DB_PASSWORD"),
                dbname=cfg(env, "DB_NAME", "stock_db"),
            )
            self.placeholder = "%s"
        elif self.db_type == "sqlite":
            SQLITE_PATH.parent.mkdir(parents=True, exist_ok=True)
            self.conn = sqlite3.connect(SQLITE_PATH)
            self.placeholder = "?"
        else:
            raise RuntimeError("DB_TYPE 只支持 postgresql 或 sqlite")
        self.init_schema()

    def close(self):
        self.conn.close()

    def execute(self, sql, params=()):
        cur = self.conn.cursor()
        cur.execute(sql, params)
        return cur

    def executemany(self, sql, rows):
        cur = self.conn.cursor()
        cur.executemany(sql, rows)
        return cur

    def commit(self):
        self.conn.commit()

    def init_schema(self):
        if self.db_type == "postgresql":
            statements = [
                """CREATE TABLE IF NOT EXISTS stock_basic(ts_code TEXT PRIMARY KEY, name TEXT NOT NULL, industry TEXT, list_date TEXT, market TEXT, is_st BOOLEAN NOT NULL, updated_at BIGINT NOT NULL)""",
                """CREATE TABLE IF NOT EXISTS daily_quotes(ts_code TEXT NOT NULL, trade_date TEXT NOT NULL, open DOUBLE PRECISION, high DOUBLE PRECISION, low DOUBLE PRECISION, close DOUBLE PRECISION, pre_close DOUBLE PRECISION, change_value DOUBLE PRECISION, pct_chg DOUBLE PRECISION, vol DOUBLE PRECISION, amount DOUBLE PRECISION, PRIMARY KEY(ts_code, trade_date))""",
                "CREATE INDEX IF NOT EXISTS idx_daily_quotes_ts_code ON daily_quotes(ts_code)",
                "CREATE INDEX IF NOT EXISTS idx_daily_quotes_trade_date ON daily_quotes(trade_date)",
                """CREATE TABLE IF NOT EXISTS financial_snapshot(ts_code TEXT NOT NULL, report_date TEXT NOT NULL, ann_date TEXT, short_debt_coverage DOUBLE PRECISION, interest_bearing_debt_ratio DOUBLE PRECISION, cashflow_quality DOUBLE PRECISION, debt_to_assets DOUBLE PRECISION, financial_safety_score DOUBLE PRECISION, risk_text TEXT, updated_at BIGINT NOT NULL, PRIMARY KEY(ts_code, report_date))""",
                """CREATE TABLE IF NOT EXISTS score_result(ts_code TEXT NOT NULL, trade_date TEXT NOT NULL, name TEXT, industry TEXT, total_score DOUBLE PRECISION NOT NULL, signal_level TEXT NOT NULL, price_position_score DOUBLE PRECISION NOT NULL, macd_multi_period_score DOUBLE PRECISION NOT NULL, financial_safety_score DOUBLE PRECISION NOT NULL, ownership_score DOUBLE PRECISION NOT NULL, price_percentile DOUBLE PRECISION, distance_to_low DOUBLE PRECISION, monthly_macd_status TEXT, weekly_macd_status TEXT, daily_macd_status TEXT, reason TEXT, has_ten_year_data BOOLEAN NOT NULL, data_warning TEXT, current_close DOUBLE PRECISION, ten_year_low DOUBLE PRECISION, daily_count INTEGER, weekly_count INTEGER, monthly_count INTEGER, updated_at BIGINT NOT NULL, PRIMARY KEY(ts_code, trade_date))""",
                "CREATE INDEX IF NOT EXISTS idx_score_result_trade_date ON score_result(trade_date)",
                "CREATE INDEX IF NOT EXISTS idx_score_result_total_score ON score_result(total_score)",
                "CREATE TABLE IF NOT EXISTS update_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS failed_tasks(ts_code TEXT PRIMARY KEY, name TEXT, reason TEXT NOT NULL, retry_count INTEGER NOT NULL, last_failed_at BIGINT NOT NULL, permanently_failed BOOLEAN NOT NULL)",
            ]
        else:
            statements = [
                "CREATE TABLE IF NOT EXISTS stock_basic(tsCode TEXT PRIMARY KEY, name TEXT NOT NULL, industry TEXT, listDate TEXT, market TEXT, isSt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS daily_quotes(tsCode TEXT NOT NULL, tradeDate TEXT NOT NULL, open REAL, high REAL, low REAL, close REAL, preClose REAL, changeValue REAL, pctChg REAL, vol REAL, amount REAL, PRIMARY KEY(tsCode, tradeDate))",
                "CREATE INDEX IF NOT EXISTS idx_daily_quotes_tsCode ON daily_quotes(tsCode)",
                "CREATE INDEX IF NOT EXISTS idx_daily_quotes_tradeDate ON daily_quotes(tradeDate)",
                "CREATE TABLE IF NOT EXISTS financial_snapshot(tsCode TEXT NOT NULL, reportDate TEXT NOT NULL, annDate TEXT, shortDebtCoverage REAL, interestBearingDebtRatio REAL, cashflowQuality REAL, debtToAssets REAL, financialSafetyScore REAL, riskText TEXT, updatedAt INTEGER NOT NULL, PRIMARY KEY(tsCode, reportDate))",
                "CREATE TABLE IF NOT EXISTS score_result(tsCode TEXT NOT NULL, tradeDate TEXT NOT NULL, name TEXT, industry TEXT, totalScore REAL NOT NULL, signalLevel TEXT NOT NULL, pricePositionScore REAL NOT NULL, macdMultiPeriodScore REAL NOT NULL, financialSafetyScore REAL NOT NULL, ownershipScore REAL NOT NULL, pricePercentile REAL, distanceToLow REAL, monthlyMacdStatus TEXT, weeklyMacdStatus TEXT, dailyMacdStatus TEXT, reason TEXT, hasTenYearData INTEGER NOT NULL, dataWarning TEXT, currentClose REAL, tenYearLow REAL, dailyCount INTEGER, weeklyCount INTEGER, monthlyCount INTEGER, updatedAt INTEGER NOT NULL, PRIMARY KEY(tsCode, tradeDate))",
                "CREATE INDEX IF NOT EXISTS idx_score_result_tradeDate ON score_result(tradeDate)",
                "CREATE INDEX IF NOT EXISTS idx_score_result_totalScore ON score_result(totalScore)",
                "CREATE TABLE IF NOT EXISTS update_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS failed_tasks(tsCode TEXT PRIMARY KEY, name TEXT, reason TEXT NOT NULL, retryCount INTEGER NOT NULL, lastFailedAt INTEGER NOT NULL, permanentlyFailed INTEGER NOT NULL)",
            ]
        for sql in statements:
            self.execute(sql)
        self.commit()

    def upsert_stock_basic(self, items):
        now = int(time.time() * 1000)
        rows = [(str(x.get("ts_code") or "").upper(), str(x.get("name") or ""), x.get("industry"), x.get("list_date"), x.get("market"), "ST" in str(x.get("name") or "").upper(), now) for x in items if x.get("ts_code")]
        if self.db_type == "postgresql":
            sql = "INSERT INTO stock_basic(ts_code,name,industry,list_date,market,is_st,updated_at) VALUES(%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(ts_code) DO UPDATE SET name=EXCLUDED.name, industry=EXCLUDED.industry, list_date=EXCLUDED.list_date, market=EXCLUDED.market, is_st=EXCLUDED.is_st, updated_at=EXCLUDED.updated_at"
        else:
            rows = [(a, b, c, d, e, 1 if f else 0, g) for a, b, c, d, e, f, g in rows]
            sql = "INSERT OR REPLACE INTO stock_basic(tsCode,name,industry,listDate,market,isSt,updatedAt) VALUES(?,?,?,?,?,?,?)"
        self.executemany(sql, rows)
        self.commit()

    def upsert_daily(self, dailies):
        rows = [(str(r.get("ts_code") or ""), str(r.get("trade_date") or ""), fnum(r.get("open")), fnum(r.get("high")), fnum(r.get("low")), fnum(r.get("close")), fnum(r.get("pre_close")), fnum(r.get("change")), fnum(r.get("pct_chg")), fnum(r.get("vol")), fnum(r.get("amount"))) for r in dailies if r.get("ts_code") and r.get("trade_date")]
        if self.db_type == "postgresql":
            sql = "INSERT INTO daily_quotes(ts_code,trade_date,open,high,low,close,pre_close,change_value,pct_chg,vol,amount) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(ts_code,trade_date) DO UPDATE SET open=EXCLUDED.open, high=EXCLUDED.high, low=EXCLUDED.low, close=EXCLUDED.close, pre_close=EXCLUDED.pre_close, change_value=EXCLUDED.change_value, pct_chg=EXCLUDED.pct_chg, vol=EXCLUDED.vol, amount=EXCLUDED.amount"
        else:
            sql = "INSERT OR REPLACE INTO daily_quotes(tsCode,tradeDate,open,high,low,close,preClose,changeValue,pctChg,vol,amount) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
        self.executemany(sql, rows)
        self.commit()

    def upsert_financial(self, ts_code, report, score):
        now = int(time.time() * 1000)
        if self.db_type == "postgresql":
            self.execute("INSERT INTO financial_snapshot(ts_code,report_date,ann_date,financial_safety_score,risk_text,updated_at) VALUES(%s,%s,%s,%s,%s,%s) ON CONFLICT(ts_code,report_date) DO UPDATE SET financial_safety_score=EXCLUDED.financial_safety_score, risk_text=EXCLUDED.risk_text, updated_at=EXCLUDED.updated_at", (ts_code, report, None, score, "电脑端财务评分", now))
        else:
            self.execute("INSERT OR REPLACE INTO financial_snapshot(tsCode,reportDate,annDate,financialSafetyScore,riskText,updatedAt) VALUES(?,?,?,?,?,?)", (ts_code, report, None, score, "电脑端财务评分", now))
        self.commit()

    def upsert_score(self, item, dailies, score_values):
        if self.db_type == "postgresql":
            sql = """INSERT INTO score_result(ts_code,trade_date,name,industry,total_score,signal_level,price_position_score,macd_multi_period_score,financial_safety_score,ownership_score,price_percentile,distance_to_low,monthly_macd_status,weekly_macd_status,daily_macd_status,reason,has_ten_year_data,data_warning,current_close,ten_year_low,daily_count,weekly_count,monthly_count,updated_at) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(ts_code,trade_date) DO UPDATE SET total_score=EXCLUDED.total_score, signal_level=EXCLUDED.signal_level, updated_at=EXCLUDED.updated_at"""
        else:
            sql = """INSERT OR REPLACE INTO score_result(tsCode,tradeDate,name,industry,totalScore,signalLevel,pricePositionScore,macdMultiPeriodScore,financialSafetyScore,ownershipScore,pricePercentile,distanceToLow,monthlyMacdStatus,weeklyMacdStatus,dailyMacdStatus,reason,hasTenYearData,dataWarning,currentClose,tenYearLow,dailyCount,weeklyCount,monthlyCount,updatedAt) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
        self.execute(sql, score_values)
        self.commit()

    def upsert_failed(self, ts_code, name, reason):
        now = int(time.time() * 1000)
        if self.db_type == "postgresql":
            self.execute("INSERT INTO failed_tasks(ts_code,name,reason,retry_count,last_failed_at,permanently_failed) VALUES(%s,%s,%s,%s,%s,%s) ON CONFLICT(ts_code) DO UPDATE SET name=EXCLUDED.name, reason=EXCLUDED.reason, retry_count=failed_tasks.retry_count+1, last_failed_at=EXCLUDED.last_failed_at", (ts_code, name, str(reason)[:300], 1, now, False))
        else:
            self.execute("INSERT OR REPLACE INTO failed_tasks(tsCode,name,reason,retryCount,lastFailedAt,permanentlyFailed) VALUES(?,?,?,?,?,?)", (ts_code, name, str(reason)[:300], 1, now, 0))
        self.commit()

    def update_meta(self, key, value):
        now = int(time.time() * 1000)
        if self.db_type == "postgresql":
            self.execute("INSERT INTO update_meta(key,value,updated_at) VALUES(%s,%s,%s) ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value, updated_at=EXCLUDED.updated_at", (key, value, now))
        else:
            self.execute("INSERT OR REPLACE INTO update_meta(key,value,updatedAt) VALUES(?,?,?)", (key, value, now))
        self.commit()

    def latest_scores(self):
        if self.db_type == "postgresql":
            cur = self.execute("SELECT ts_code AS \"tsCode\", name AS \"stockName\", industry, total_score AS \"totalScore\", signal_level AS \"signalLevel\" FROM score_result WHERE trade_date=(SELECT MAX(trade_date) FROM score_result) ORDER BY total_score DESC")
        else:
            cur = self.execute("SELECT tsCode, name AS stockName, industry, totalScore, signalLevel FROM score_result WHERE tradeDate=(SELECT MAX(tradeDate) FROM score_result) ORDER BY totalScore DESC")
        names = [c[0] for c in cur.description]
        return [dict(zip(names, row)) for row in cur.fetchall()]


def load_token(env):
    return cfg(env, "TUSHARE_TOKEN")

def post_tushare(token, api_name, params, fields):
    body = json.dumps({"api_name": api_name, "token": token, "params": params, "fields": fields}, ensure_ascii=False).encode("utf-8")
    delays = [5, 15, 30]
    last_error = None
    for attempt in range(3):
        try:
            req = request.Request(API_URL, data=body, headers={"Content-Type": "application/json; charset=utf-8"}, method="POST")
            with request.urlopen(req, timeout=180) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
            if payload.get("code") != 0:
                raise RuntimeError(payload.get("msg") or f"Tushare code={payload.get('code')}")
            return payload.get("data") or {}
        except Exception as exc:
            last_error = exc
            if attempt < 2:
                time.sleep(delays[attempt])
    raise last_error


def rows(table):
    fields = table.get("fields") or []
    items = table.get("items") or []
    return [{name: row[i] if i < len(row) else None for i, name in enumerate(fields)} for row in items]


def fnum(value):
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def ema(values, period):
    if not values:
        return []
    k = 2.0 / (period + 1)
    out = [values[0]]
    cur = values[0]
    for v in values[1:]:
        cur = v * k + cur * (1 - k)
        out.append(cur)
    return out


def macd_status(closes):
    if len(closes) < 3:
        return "NEUTRAL"
    dif = [a - b for a, b in zip(ema(closes, 12), ema(closes, 26))]
    dea = ema(dif, 9)
    bar = [(d - e) * 2.0 for d, e in zip(dif, dea)]
    if len(bar) < 3:
        return "NEUTRAL"
    b0, b1, b2 = bar[-3], bar[-2], bar[-1]
    if dif[-2] <= dea[-2] and dif[-1] > dea[-1]:
        return "GOLDEN_CROSS"
    if dif[-2] >= dea[-2] and dif[-1] < dea[-1]:
        return "DEAD_CROSS"
    if b2 < 0 and b0 < 0 and b1 < 0:
        if abs(b0) > abs(b1) > abs(b2):
            return "GREEN_SHRINKING"
        if abs(b0) < abs(b1) < abs(b2):
            return "GREEN_EXPANDING"
    if b2 > 0 and b0 > 0 and b1 > 0 and b0 < b1 < b2:
        return "RED_EXPANDING"
    return "RED_WEAKENING" if b2 > 0 else "NEUTRAL"


def status_score(s):
    return {"GREEN_SHRINKING": 75.0, "GOLDEN_CROSS": 90.0, "RED_EXPANDING": 85.0, "RED_WEAKENING": 65.0, "GREEN_EXPANDING": 30.0, "DEAD_CROSS": 20.0, "NEUTRAL": 50.0}.get(s, 50.0)


def resample(dailies, key_len):
    buckets = {}
    for r in dailies:
        d = str(r.get("trade_date") or "")
        c = fnum(r.get("close"))
        if len(d) >= key_len and c is not None:
            buckets[d[:key_len]] = c
    return [v for _, v in sorted(buckets.items())]


def price_breakdown(closes, current):
    ordered = sorted(closes)
    rank = next((i for i, v in enumerate(ordered) if v >= current), len(ordered))
    pct = rank / max(len(ordered) - 1, 1)
    low = min(ordered)
    dist = math.inf if low <= 0 else current / low - 1.0
    base = 100.0 if pct <= 0.10 else 85.0 if pct <= 0.20 else 70.0 if pct <= 0.30 else 55.0 if pct <= 0.40 else 30.0
    base += 10.0 if dist <= 0.20 else 5.0 if dist <= 0.40 else -10.0 if dist > 1.0 else 0.0
    return max(0.0, min(100.0, base)), pct, dist, low


def ratio(n, d):
    return None if n is None or d is None or d == 0 else n / d


def score_financial(balance, cashflow, income, indicator):
    b = max(balance, key=lambda r: str(r.get("end_date") or ""), default={})
    ind = max(indicator, key=lambda r: str(r.get("end_date") or ""), default={})
    st = fnum(b.get("st_borr")) or 0.0
    due = fnum(b.get("non_cur_liab_due_1y")) or 0.0
    short_cov = ratio(fnum(b.get("money_cap")), st + due)
    interest = st + due + (fnum(b.get("lt_borr")) or 0.0) + (fnum(b.get("bond_payable")) or 0.0)
    interest_ratio = ratio(interest, fnum(b.get("total_assets")))
    cash_sum = sum(fnum(r.get("n_cashflow_act")) or 0.0 for r in sorted(cashflow, key=lambda r: str(r.get("end_date") or ""), reverse=True)[:4])
    income_sum = sum(fnum(r.get("n_income")) or 0.0 for r in sorted(income, key=lambda r: str(r.get("end_date") or ""), reverse=True)[:4])
    cash_quality = ratio(cash_sum, income_sum)
    debt = fnum(ind.get("debt_to_assets"))
    if debt is not None and debt > 1:
        debt /= 100.0
    if debt is None:
        debt = ratio(fnum(b.get("total_liab")), fnum(b.get("total_assets")))
    s1 = 60.0 if short_cov is None else 100.0 if short_cov > 2 else 80.0 if short_cov >= 1 else 60.0 if short_cov >= 0.5 else 30.0
    s2 = 60.0 if interest_ratio is None else 100.0 if interest_ratio < 0.2 else 80.0 if interest_ratio <= 0.4 else 60.0 if interest_ratio <= 0.6 else 30.0
    s3 = 60.0 if cash_quality is None else 100.0 if cash_quality > 1 else 80.0 if cash_quality >= 0.5 else 60.0 if cash_quality >= 0 else 30.0
    s4 = 60.0 if debt is None else 100.0 if debt < 0.4 else 80.0 if debt <= 0.6 else 60.0 if debt <= 0.75 else 30.0
    return s1 * 0.35 + s2 * 0.25 + s3 * 0.25 + s4 * 0.15, max([str(x.get("end_date") or "") for x in balance + cashflow + income + indicator] or [""])


def signal(total):
    return "核心观察" if total >= 85 else "优先观察" if total >= 75 else "低位潜伏" if total >= 65 else "只观察" if total >= 55 else "剔除/不关注"


def listed_less_than_five_years(list_date, end_date):
    if len(list_date or "") != 8:
        return False
    return datetime.strptime(list_date, "%Y%m%d") > datetime.strptime(end_date, "%Y%m%d") - timedelta(days=365 * 5)


def has_ten_years(first, latest):
    if not first or not latest:
        return False
    return (datetime.strptime(latest, "%Y%m%d") - datetime.strptime(first, "%Y%m%d")).days >= 3650


def save_failed(ts_code, name, reason):
    FAILED_FILE.parent.mkdir(parents=True, exist_ok=True)
    new_file = not FAILED_FILE.exists()
    with FAILED_FILE.open("a", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        if new_file:
            w.writerow(["ts_code", "name", "reason", "failed_at"])
        w.writerow([ts_code, name or "", str(reason)[:300], datetime.now().isoformat()])


def load_progress(end_date, start_date, max_scan):
    if not PROGRESS_FILE.exists():
        return None
    try:
        data = json.loads(PROGRESS_FILE.read_text(encoding="utf-8"))
    except Exception:
        return None
    if data.get("end_date") == end_date and data.get("start_date") == start_date and int(data.get("max_scan_count") or 0) == max_scan and data.get("status") != "completed":
        return data
    return None


def save_progress(data):
    PROGRESS_FILE.parent.mkdir(parents=True, exist_ok=True)
    data["updated_at"] = datetime.now(timezone.utc).isoformat()
    PROGRESS_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def fetch_financial_score(token, db, ts_code):
    try:
        balance = rows(post_tushare(token, "balance_sheet", {"ts_code": ts_code}, "ts_code,end_date,money_cap,st_borr,non_cur_liab_due_1y,lt_borr,bond_payable,total_assets,total_liab"))
        cashflow = rows(post_tushare(token, "cashflow", {"ts_code": ts_code}, "ts_code,end_date,n_cashflow_act"))
        income = rows(post_tushare(token, "income", {"ts_code": ts_code}, "ts_code,end_date,n_income"))
        indicator = rows(post_tushare(token, "fina_indicator", {"ts_code": ts_code}, "ts_code,end_date,debt_to_assets"))
        score, report = score_financial(balance, cashflow, income, indicator)
        if report:
            db.upsert_financial(ts_code, report, score)
        return score
    except Exception as exc:
        save_failed(ts_code, "", f"financial fallback 60: {exc}")
        return 60.0


def score_and_save(db, item, dailies, fin_score):
    closes = [fnum(r.get("close")) for r in dailies]
    closes = [v for v in closes if v is not None]
    current = closes[-1]
    p_score, pct, dist, low = price_breakdown(closes, current)
    monthly = resample(dailies, 6)
    weekly = resample(dailies, 6)
    m, w, d = macd_status(monthly), macd_status(weekly), macd_status(closes)
    macd_score = status_score(m) * 0.45 + status_score(w) * 0.35 + status_score(d) * 0.20
    own_score = 60.0
    total = p_score * 0.30 + macd_score * 0.35 + fin_score * 0.25 + own_score * 0.10
    latest = str(dailies[-1].get("trade_date"))
    first = str(dailies[0].get("trade_date"))
    ten = has_ten_years(first, latest)
    warning = None if ten else "历史数据不足10年，评分可信度下降"
    values = (str(item.get("ts_code")).upper(), latest, item.get("name"), item.get("industry"), round(total, 2), signal(total), p_score, macd_score, fin_score, own_score, pct, dist, m, w, d, "电脑端扫描结果", ten if db.db_type == "postgresql" else (1 if ten else 0), warning, current, low, len(dailies), len(weekly), len(monthly), int(time.time() * 1000))
    db.upsert_score(item, dailies, values)
    return {"tsCode": str(item.get("ts_code")).upper(), "stockName": item.get("name"), "industry": item.get("industry"), "isScored": True, "totalScore": round(total, 2), "signalLevel": signal(total), "dailyDataSource": f"电脑{db.db_type}", "financialDataSource": f"电脑{db.db_type}"}


def write_outputs(results, payload):
    results = sorted(results, key=lambda r: -float(r.get("totalScore") or 0.0))
    OUT_CSV.parent.mkdir(parents=True, exist_ok=True)
    with OUT_CSV.open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=["tsCode", "stockName", "industry", "totalScore", "signalLevel"])
        w.writeheader()
        for r in results:
            w.writerow({k: r.get(k, "") for k in w.fieldnames})
    rows_html = "\n".join(f"<tr><td>{html.escape(str(r.get('tsCode','')))}</td><td>{html.escape(str(r.get('stockName','')))}</td><td>{html.escape(str(r.get('industry','')))}</td><td>{r.get('totalScore','')}</td><td>{html.escape(str(r.get('signalLevel','')))}</td></tr>" for r in results)
    OUT_HTML.write_text(f"<!doctype html><meta charset='utf-8'><title>LongTerm Scores</title><h1>LongTerm Scores</h1><p>Updated: {html.escape(payload['updatedAt'])}</p><table border='1' cellspacing='0' cellpadding='6'><tr><th>代码</th><th>名称</th><th>行业</th><th>总分</th><th>等级</th></tr>{rows_html}</table>", encoding="utf-8")
    LATEST_JSON.parent.mkdir(parents=True, exist_ok=True)
    LATEST_JSON.write_text(json.dumps({**payload, "results": results}, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    env = load_env_file()
    if not env:
        sys.exit(1)
    token = load_token(env)
    if not token:
        print(".env 缺少 TUSHARE_TOKEN", file=sys.stderr)
        sys.exit(1)
    max_scan = max(1, min(int(os.environ.get("MAX_SCAN_COUNT") or env.get("MAX_SCAN_COUNT") or "100"), 1000))
    end_date = (os.environ.get("END_DATE") or env.get("END_DATE") or datetime.now().strftime("%Y%m%d")).strip()
    start_date = (os.environ.get("START_DATE") or env.get("START_DATE") or (datetime.strptime(end_date, "%Y%m%d") - timedelta(days=3650)).strftime("%Y%m%d")).strip()

    try:
        db = ScanDatabase(env)
    except Exception as exc:
        print(f"数据库连接失败: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(2)

    try:
        stock_basic = rows(post_tushare(token, "stock_basic", {"list_status": "L"}, "ts_code,name,industry,list_date,market"))
    except Exception as exc:
        print(f"stock_basic 拉取失败，数据库无法初始化: {type(exc).__name__}: {exc}", file=sys.stderr)
        db.close()
        sys.exit(3)
    db.upsert_stock_basic(stock_basic)

    candidates = []
    for item in sorted(stock_basic, key=lambda r: str(r.get("ts_code") or "")):
        ts_code = str(item.get("ts_code") or "").upper()
        name = str(item.get("name") or "")
        if not (ts_code.endswith(".SZ") or ts_code.endswith(".SH")) or "ST" in name.upper() or listed_less_than_five_years(str(item.get("list_date") or ""), end_date):
            continue
        candidates.append(item)
        if len(candidates) >= max_scan:
            break

    progress = load_progress(end_date, start_date, max_scan) or {"scan_id": f"scan-{int(time.time())}", "end_date": end_date, "start_date": start_date, "max_scan_count": max_scan, "current_index": 0, "success_count": 0, "failed_count": 0, "status": "running"}
    start_idx = int(progress.get("current_index") or 0)
    for idx in range(start_idx, len(candidates)):
        item = candidates[idx]
        ts_code = str(item.get("ts_code") or "").upper()
        try:
            dailies = rows(post_tushare(token, "daily", {"ts_code": ts_code, "start_date": start_date, "end_date": end_date}, DAILY_FIELDS))
            dailies = sorted(dailies, key=lambda r: str(r.get("trade_date") or ""))
            if not dailies:
                raise RuntimeError("daily empty")
            db.upsert_daily(dailies)
            closes = [fnum(r.get("close")) for r in dailies]
            closes = [v for v in closes if v is not None]
            if len(closes) < 500:
                raise RuntimeError("历史数据不足最低计算要求")
            fin_score = fetch_financial_score(token, db, ts_code)
            score_and_save(db, item, dailies, fin_score)
            progress["success_count"] = int(progress.get("success_count") or 0) + 1
        except Exception as exc:
            progress["failed_count"] = int(progress.get("failed_count") or 0) + 1
            save_failed(ts_code, item.get("name"), exc)
            db.upsert_failed(ts_code, item.get("name"), exc)
        progress["current_index"] = idx + 1
        save_progress(progress)
        print(f"{idx + 1}/{len(candidates)} {ts_code} success={progress['success_count']} failed={progress['failed_count']}", flush=True)

    progress["status"] = "completed"
    save_progress(progress)
    results = db.latest_scores()
    payload = {"updatedAt": datetime.now(timezone.utc).isoformat(), "endDate": end_date, "maxScanCount": max_scan, "successCount": int(progress.get("success_count") or 0), "failedCount": int(progress.get("failed_count") or 0), "skippedCount": 0, "estimatedWaitMinutes": max(1, math.ceil(max_scan * 0.5))}
    write_outputs(results, payload)
    db.update_meta("latest_trade_date", end_date)
    db.close()
    print(f"数据库类型: {cfg(env, 'DB_TYPE', 'postgresql') or 'postgresql'}")
    print(f"CSV: {OUT_CSV}")
    print(f"HTML: {OUT_HTML}")


if __name__ == "__main__":
    main()
