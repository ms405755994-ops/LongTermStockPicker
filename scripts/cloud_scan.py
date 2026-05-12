#!/usr/bin/env python3
import json
import math
import os
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib import request

API_URL = "http://api.tushare.pro"
OUT_FILE = Path("docs/results/latest.json")


def post_tushare(token, api_name, params, fields):
    body = json.dumps(
        {
            "api_name": api_name,
            "token": token,
            "params": params,
            "fields": fields,
        },
        ensure_ascii=False,
    ).encode("utf-8")
    req = request.Request(
        API_URL,
        data=body,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    with request.urlopen(req, timeout=60) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    if payload.get("code") != 0:
        raise RuntimeError(payload.get("msg") or f"Tushare code={payload.get('code')}")
    return payload.get("data") or {}


def rows(table):
    fields = table.get("fields") or []
    items = table.get("items") or []
    out = []
    for row in items:
        out.append({name: row[i] if i < len(row) else None for i, name in enumerate(fields)})
    return out


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
    current = values[0]
    for value in values[1:]:
        current = value * k + current * (1 - k)
        out.append(current)
    return out


def macd_status(closes):
    if len(closes) < 3:
        return "NEUTRAL"
    ema12 = ema(closes, 12)
    ema26 = ema(closes, 26)
    dif = [a - b for a, b in zip(ema12, ema26)]
    dea = ema(dif, 9)
    bar = [(d - e) * 2.0 for d, e in zip(dif, dea)]
    if len(dif) < 2 or len(dea) < 2 or len(bar) < 3:
        return "NEUTRAL"
    b0, b1, b2 = bar[-3], bar[-2], bar[-1]
    d_prev, d_curr = dif[-2], dif[-1]
    e_prev, e_curr = dea[-2], dea[-1]
    if d_prev <= e_prev and d_curr > e_curr:
        return "GOLDEN_CROSS"
    if d_prev >= e_prev and d_curr < e_curr:
        return "DEAD_CROSS"
    if b2 < 0 and b0 < 0 and b1 < 0:
        if abs(b0) > abs(b1) > abs(b2):
            return "GREEN_SHRINKING"
        if abs(b0) < abs(b1) < abs(b2):
            return "GREEN_EXPANDING"
    if b2 > 0 and b0 > 0 and b1 > 0 and b0 < b1 < b2:
        return "RED_EXPANDING"
    return "RED_WEAKENING" if b2 > 0 else "NEUTRAL"


def status_score(status):
    return {
        "GREEN_SHRINKING": 75.0,
        "GOLDEN_CROSS": 90.0,
        "RED_EXPANDING": 85.0,
        "RED_WEAKENING": 65.0,
        "GREEN_EXPANDING": 30.0,
        "DEAD_CROSS": 20.0,
        "NEUTRAL": 50.0,
    }.get(status, 50.0)


def resample_period(dailies, key_len):
    buckets = {}
    for row in dailies:
        date = str(row.get("trade_date", ""))
        if len(date) >= key_len:
            buckets[date[:key_len]] = fnum(row.get("close"))
    return [v for _, v in sorted(buckets.items()) if v is not None]


def price_score(closes, current):
    if not closes:
        return 30.0
    ordered = sorted(closes)
    rank = next((i for i, value in enumerate(ordered) if value >= current), len(ordered))
    percentile = rank / max(len(ordered) - 1, 1)
    low = min(ordered)
    distance = math.inf if low <= 0 else current / low - 1.0
    if percentile <= 0.10:
        base = 100.0
    elif percentile <= 0.20:
        base = 85.0
    elif percentile <= 0.30:
        base = 70.0
    elif percentile <= 0.40:
        base = 55.0
    else:
        base = 30.0
    if distance <= 0.20:
        base += 10
    elif distance <= 0.40:
        base += 5
    elif distance > 1.0:
        base -= 10
    return max(0.0, min(100.0, base))


def ratio(n, d):
    if n is None or d is None or d == 0:
        return None
    return n / d


def score_financial(balance_rows, cashflow_rows, income_rows, indicator_rows):
    balance = max(balance_rows, key=lambda r: str(r.get("end_date", "")), default={})
    indicator = max(indicator_rows, key=lambda r: str(r.get("end_date", "")), default={})
    short_borrow = fnum(balance.get("st_borr")) or 0.0
    due_1y = fnum(balance.get("non_cur_liab_due_1y")) or 0.0
    short_debt = short_borrow + due_1y
    short_debt_coverage = ratio(fnum(balance.get("money_cap")), short_debt)
    interest_debt = short_borrow + (fnum(balance.get("lt_borr")) or 0.0) + (fnum(balance.get("bond_payable")) or 0.0) + due_1y
    interest_ratio = ratio(interest_debt, fnum(balance.get("total_assets")))
    cash_sum = sum(fnum(r.get("n_cashflow_act")) or 0.0 for r in sorted(cashflow_rows, key=lambda r: str(r.get("end_date", "")), reverse=True)[:4])
    income_sum = sum(fnum(r.get("n_income")) or 0.0 for r in sorted(income_rows, key=lambda r: str(r.get("end_date", "")), reverse=True)[:4])
    cash_quality = ratio(cash_sum, income_sum)
    debt_ratio = fnum(indicator.get("debt_to_assets"))
    if debt_ratio is not None and debt_ratio > 1:
        debt_ratio /= 100.0
    if debt_ratio is None:
        debt_ratio = ratio(fnum(balance.get("total_liab")), fnum(balance.get("total_assets")))

    def short_score(v):
        if v is None or not math.isfinite(v):
            return 60.0
        return 100.0 if v > 2 else 80.0 if v >= 1 else 60.0 if v >= 0.5 else 30.0

    def interest_score(v):
        if v is None or not math.isfinite(v):
            return 60.0
        return 100.0 if v < 0.2 else 80.0 if v <= 0.4 else 60.0 if v <= 0.6 else 30.0

    def cash_score(v):
        if v is None or not math.isfinite(v):
            return 60.0
        return 100.0 if v > 1 else 80.0 if v >= 0.5 else 60.0 if v >= 0 else 30.0

    def debt_score(v):
        if v is None or not math.isfinite(v):
            return 60.0
        return 100.0 if v < 0.4 else 80.0 if v <= 0.6 else 60.0 if v <= 0.75 else 30.0

    return short_score(short_debt_coverage) * 0.35 + interest_score(interest_ratio) * 0.25 + cash_score(cash_quality) * 0.25 + debt_score(debt_ratio) * 0.15


def signal(total):
    if total >= 85:
        return "核心观察"
    if total >= 75:
        return "优先观察"
    if total >= 65:
        return "低位潜伏"
    if total >= 55:
        return "只观察"
    return "剔除/不关注"


def listed_less_than_five_years(list_date, end_date):
    if len(list_date or "") != 8:
        return False
    return datetime.strptime(list_date, "%Y%m%d") > datetime.strptime(end_date, "%Y%m%d") - timedelta(days=365 * 5)


def main():
    token = os.environ.get("TUSHARE_TOKEN", "").strip()
    if not token:
        print("Missing TUSHARE_TOKEN secret", file=sys.stderr)
        sys.exit(1)
    max_scan = max(1, min(int(os.environ.get("MAX_SCAN_COUNT") or "100"), 1000))
    end_date = (os.environ.get("END_DATE") or datetime.now().strftime("%Y%m%d")).strip()
    start_date = (os.environ.get("START_DATE") or (datetime.strptime(end_date, "%Y%m%d") - timedelta(days=3650)).strftime("%Y%m%d")).strip()

    stock_basic = rows(post_tushare(token, "stock_basic", {"list_status": "L"}, "ts_code,name,industry,list_date,market"))
    candidates = []
    skipped = 0
    for item in sorted(stock_basic, key=lambda r: str(r.get("ts_code"))):
        ts_code = str(item.get("ts_code") or "").upper()
        name = str(item.get("name") or "")
        if not (ts_code.endswith(".SZ") or ts_code.endswith(".SH")):
            continue
        if "ST" in name.upper() or listed_less_than_five_years(str(item.get("list_date") or ""), end_date):
            skipped += 1
            continue
        candidates.append(item)
        if len(candidates) >= max_scan:
            break

    results = []
    failed = 0
    for idx, item in enumerate(candidates, start=1):
        ts_code = str(item.get("ts_code")).upper()
        try:
            dailies = rows(post_tushare(token, "daily", {"ts_code": ts_code, "start_date": start_date, "end_date": end_date}, "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount"))
            dailies = sorted(dailies, key=lambda r: str(r.get("trade_date", "")))
            closes = [fnum(r.get("close")) for r in dailies]
            closes = [v for v in closes if v is not None]
            if len(closes) < 500:
                results.append({"tsCode": ts_code, "stockName": item.get("name"), "industry": item.get("industry"), "isScored": False, "totalScore": 0.0, "signalLevel": "—", "exclusionReason": "历史数据不足"})
                skipped += 1
                continue
            monthly = resample_period(dailies, 6)
            weekly = resample_period(dailies, 6)
            macd_score = status_score(macd_status(monthly)) * 0.45 + status_score(macd_status(weekly)) * 0.35 + status_score(macd_status(closes)) * 0.20
            p_score = price_score(closes, closes[-1])

            balance = rows(post_tushare(token, "balance_sheet", {"ts_code": ts_code}, "ts_code,end_date,money_cap,st_borr,non_cur_liab_due_1y,lt_borr,bond_payable,total_assets,total_liab"))
            cashflow = rows(post_tushare(token, "cashflow", {"ts_code": ts_code}, "ts_code,end_date,n_cashflow_act"))
            income = rows(post_tushare(token, "income", {"ts_code": ts_code}, "ts_code,end_date,n_income"))
            indicator = rows(post_tushare(token, "fina_indicator", {"ts_code": ts_code}, "ts_code,end_date,debt_to_assets"))
            fin_score = score_financial(balance, cashflow, income, indicator)
            own_score = 60.0
            total = p_score * 0.30 + macd_score * 0.35 + fin_score * 0.25 + own_score * 0.10
            results.append(
                {
                    "tsCode": ts_code,
                    "stockName": item.get("name"),
                    "industry": item.get("industry"),
                    "isScored": True,
                    "totalScore": round(total, 2),
                    "signalLevel": signal(total),
                    "dailyDataSource": "云端",
                    "financialDataSource": "云端",
                }
            )
        except Exception as exc:
            failed += 1
            results.append({"tsCode": ts_code, "stockName": item.get("name"), "industry": item.get("industry"), "isScored": False, "totalScore": 0.0, "signalLevel": "异常/失败", "errorMessage": str(exc)[:160]})
        print(f"{idx}/{len(candidates)} {ts_code}", flush=True)
        time.sleep(0.25)

    scored = [r for r in results if r.get("isScored") and not r.get("errorMessage")]
    results.sort(key=lambda r: -float(r.get("totalScore") or 0.0))
    payload = {
        "updatedAt": datetime.now(timezone.utc).isoformat(),
        "endDate": end_date,
        "maxScanCount": max_scan,
        "successCount": len(scored),
        "failedCount": failed,
        "skippedCount": skipped,
        "estimatedWaitMinutes": max(1, math.ceil(max_scan * 0.5)),
        "results": results,
    }
    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
