#!/usr/bin/env python3
import json
import sys
from datetime import datetime
from pathlib import Path

from pg_score_and_export import (
    PgSchema,
    connect_postgres,
    ema,
    load_dailies,
    load_env,
    years_before,
)

SOURCE = Path("outputs/mobile/latest_score.json")
TARGET = Path("docs/results/latest_score_top100.json")
LOGIC_TARGET = Path("docs/results/strategy_logic.json")
CHART_DIR = Path("docs/results/charts")


def build_strategy_logic(payload):
    generated_at = payload.get("generated_at") or ""
    trade_date = payload.get("trade_date") or ""
    model_version = payload.get("model_version") or "LongTermStockPicker PG-V1"
    return {
        "generated_at": generated_at,
        "trade_date": trade_date,
        "model_version": model_version,
        "sections": [
            {
                "title": "模型定位",
                "body": "本模型是“中长线低位反转选股模型”，用于从全市场股票中筛选：\n"
                "- 长期价格处于前复权历史相对低位\n"
                "- 月线 / 周线 / 日线 MACD 有底部修复迹象\n"
                "- 财务风险相对可控\n"
                "- 企业性质有一定确定性\n"
                "- 适合进入中长线观察池的股票\n\n"
                "提示：本模型只用于研究和观察，不构成投资建议。",
                "emphasized": True,
            },
            {
                "title": "数据来源",
                "body": "云端读取 PostgreSQL 数据库，主要数据包括：\n"
                "- 股票基础信息 stock_basic\n"
                "- 前复权日线行情 daily_quotes + adj_factors\n"
                "- 财务数据 financial_snapshot / fina_indicator\n"
                "- 企业性质映射 company_ownership\n"
                "- 模型评分结果 score_result\n\n"
                "手机端只读取 GitHub 上的 Top100 结果和本页选股逻辑，不请求 Tushare，不连接 PostgreSQL。",
            },
            {
                "title": "硬过滤逻辑",
                "body": "以下股票优先过滤或降低优先级：\n"
                "- ST / *ST 股票\n"
                "- 上市不足 5 年\n"
                "- 日线历史数据低于 4 年，不进入榜单\n"
                "- 当前价距离 10 年最低价超过 100%，不进入榜单\n"
                "- 历史数据不足 10 年时显示风险提示\n"
                "- MACD 未出现底部修复迹象时不进入 Top100 候选\n"
                "- 财务数据严重缺失时降低可信度",
            },
            {
                "title": "总分公式",
                "body": "总分 = 价格低位分 × 30%\n"
                "     + MACD 多周期分 × 35%\n"
                "     + 财务安全分 × 25%\n"
                "     + 企业性质分 × 10%",
                "kind": "formula",
            },
            {
                "title": "价格低位分逻辑",
                "body": "使用最近 10 年前复权历史价格计算：\n"
                "- 当前价格分位\n"
                "- 距离 10 年最低价\n"
                "- 10 年窗口最低价\n\n"
                "评分参考：\n"
                "- 价格分位 ≤ 10%：高分\n"
                "- 价格分位 10%-20%：较高分\n"
                "- 价格分位 20%-30%：中高分\n"
                "- 价格分位 30%-40%：中等分\n"
                "- 高于 40%：低位优势减弱\n"
                "- 当前价距离 10 年最低价超过 100%：直接剔除\n\n"
                "如果历史数据不足 10 年，详情页会提示：“历史数据不足10年，评分可信度下降”。",
            },
            {
                "title": "MACD 多周期逻辑",
                "body": "MACD 使用前复权价格，并从日线聚合周线、月线：\n"
                "- 月线 MACD：权重 45%\n"
                "- 周线 MACD：权重 35%\n"
                "- 日线 MACD：权重 20%\n\n"
                "当前版本重点筛选“绿柱往红柱修复”的底部结构：\n"
                "- 绿柱缩短\n"
                "- 金叉\n"
                "- 红柱放大\n\n"
                "以下状态会被明显降权或过滤：\n"
                "- 绿柱继续放大\n"
                "- 死叉\n"
                "- 月线趋势继续走弱\n"
                "- 只有低位但 MACD 没有修复迹象",
                "emphasized": True,
            },
            {
                "title": "财务安全分逻辑",
                "body": "财务安全分主要参考：\n"
                "- 短债覆盖率\n"
                "- 有息负债率\n"
                "- 经营现金流质量\n"
                "- 资产负债率\n\n"
                "权重：\n"
                "- 短债覆盖率 × 35%\n"
                "- 有息负债率 × 25%\n"
                "- 经营现金流质量 × 25%\n"
                "- 资产负债率 × 15%\n\n"
                "如果财务数据缺失：默认财务分为 60，并在详情页提示“财务数据缺失，暂用默认分”。",
            },
            {
                "title": "企业性质分逻辑",
                "body": "企业性质分参考：\n"
                "- 中央国资\n"
                "- 省属国资\n"
                "- 地方国资\n"
                "- 国资参股\n"
                "- 优质民企\n"
                "- 普通民企\n"
                "- 未知\n\n"
                "企业性质不是决定性因素，只作为中长线确定性加权。",
            },
            {
                "title": "信号等级解释",
                "body": "根据信号总分分层：\n"
                "- 85 分以上：核心观察\n"
                "- 75-85 分：优先观察\n"
                "- 65-75 分：低位潜伏\n"
                "- 55-65 分：只观察\n"
                "- 55 分以下：剔除 / 不关注\n\n"
                "信号等级不是买入建议，只代表模型观察优先级。",
            },
            {
                "title": "Top100 输出逻辑",
                "body": "云端每天定时运行：\n"
                "- 17:00 收盘后更新\n"
                "- 03:00 凌晨补跑\n\n"
                "云端会：\n"
                "- 增量更新前复权行情和财务数据\n"
                "- 全市场评分\n"
                "- 剔除日线历史数据低于 4 年的股票\n"
                "- 只输出总分排名前 100 只\n"
                "- 上传 latest_score_top100.json 和 strategy_logic.json 到 GitHub\n\n"
                "手机端点击“同步 GitHub Top100”后，会同时读取最新结果和最新选股逻辑。",
            },
            {
                "title": "自选股说明",
                "body": "自选股只保存在手机本地数据库。\n"
                "用户可以在排行榜或详情页将股票加入自选。\n"
                "同步新数据不会清空自选股。\n"
                "如果自选股不在最新 Top100 中，仍然保留在自选股页，但可能显示“暂无最新评分”。",
            },
            {
                "title": "风险提示",
                "body": "本模型存在以下限制：\n"
                "- 历史数据不足 10 年会影响低位判断\n"
                "- 财务数据缺失会影响资金风险判断\n"
                "- MACD 是滞后指标\n"
                "- 低位不代表马上反转\n"
                "- 模型结果需要结合人工判断\n"
                "- 不构成投资建议",
                "emphasized": True,
            },
        ],
    }


def month_key(trade_date):
    return str(trade_date or "")[:6]


def week_key(trade_date):
    try:
        d = datetime.strptime(str(trade_date), "%Y%m%d").date()
    except ValueError:
        return str(trade_date or "")[:6]
    year, week, _ = d.isocalendar()
    return f"{year}{week:02d}"


def build_period_bars(dailies, key_func):
    buckets = []
    current_key = None
    current = None
    for row in dailies:
        key = key_func(row.get("trade_date"))
        if not key:
            continue
        if key != current_key:
            if current:
                buckets.append(current)
            current_key = key
            current = {
                "date": row.get("trade_date"),
                "open": row.get("open"),
                "high": row.get("high"),
                "low": row.get("low"),
                "close": row.get("close"),
                "volume": row.get("vol") or 0.0,
            }
            continue
        current["date"] = row.get("trade_date")
        current["high"] = max(v for v in [current.get("high"), row.get("high")] if v is not None) if row.get("high") is not None else current.get("high")
        current["low"] = min(v for v in [current.get("low"), row.get("low")] if v is not None) if row.get("low") is not None else current.get("low")
        current["close"] = row.get("close")
        current["volume"] = (current.get("volume") or 0.0) + (row.get("vol") or 0.0)
    if current:
        buckets.append(current)
    return [row for row in buckets if row.get("open") is not None and row.get("high") is not None and row.get("low") is not None and row.get("close") is not None]


def build_daily_bars(dailies):
    return [
        {
            "date": row.get("trade_date"),
            "open": row.get("open"),
            "high": row.get("high"),
            "low": row.get("low"),
            "close": row.get("close"),
            "volume": row.get("vol") or 0.0,
        }
        for row in dailies
        if row.get("open") is not None and row.get("high") is not None and row.get("low") is not None and row.get("close") is not None
    ]


def build_macd(closes):
    if not closes:
        return []
    ema12 = ema(closes, 12)
    ema26 = ema(closes, 26)
    dif = [a - b for a, b in zip(ema12, ema26)]
    dea = ema(dif, 9)
    return [
        {
            "dif": round(d, 6),
            "dea": round(e, 6),
            "bar": round((d - e) * 2.0, 6),
        }
        for d, e in zip(dif, dea)
    ]


def rounded(value, digits=4):
    if value is None:
        return None
    return round(float(value), digits)


def build_chart_points(bars):
    macd = build_macd([item["close"] for item in bars])
    points = []
    for item, macd_item in zip(bars, macd):
        points.append(
            {
                "date": item["date"],
                "open": rounded(item["open"]),
                "high": rounded(item["high"]),
                "low": rounded(item["low"]),
                "close": rounded(item["close"]),
                "volume": rounded(item.get("volume"), 2),
                "dif": macd_item["dif"],
                "dea": macd_item["dea"],
                "macd": macd_item["bar"],
            }
        )
    return points


def write_chart_files(results):
    CHART_DIR.mkdir(parents=True, exist_ok=True)
    for old in CHART_DIR.glob("*.json"):
        old.unlink()
    if not results:
        return 0
    env = load_env()
    conn = connect_postgres(env)
    written = 0
    try:
        schema = PgSchema(conn)
        for row in results:
            ts_code = row.get("ts_code")
            trade_date = row.get("trade_date")
            start_date = years_before(trade_date, 10)
            if not ts_code or not trade_date or not start_date:
                continue
            dailies = load_dailies(conn, schema, ts_code, start_date, trade_date)
            monthly = build_period_bars(dailies, month_key)
            weekly = build_period_bars(dailies, week_key)
            daily = build_daily_bars(dailies)
            payload = {
                "ts_code": ts_code,
                "name": row.get("name") or "",
                "trade_date": trade_date,
                "price_type": "qfq",
                "periods": {
                    "monthly": {"label": "月线", "points": build_chart_points(monthly)},
                    "weekly": {"label": "周线", "points": build_chart_points(weekly)},
                    "daily": {"label": "日线", "points": build_chart_points(daily)},
                },
            }
            (CHART_DIR / f"{ts_code}.json").write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
            written += 1
    finally:
        conn.close()
    return written


def main():
    if not SOURCE.exists():
        print(f"缺少 {SOURCE}，请先运行 scripts/pg_score_and_export.py", file=sys.stderr)
        sys.exit(1)
    payload = json.loads(SOURCE.read_text(encoding="utf-8"))
    results = sorted(
        payload.get("results") or [],
        key=lambda row: float(row.get("total_score") or 0.0),
        reverse=True,
    )[:100]
    out = dict(payload)
    out["total_count"] = len(results)
    out["results"] = results
    out["source_total_count"] = len(payload.get("results") or [])
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    LOGIC_TARGET.write_text(json.dumps(build_strategy_logic(payload), ensure_ascii=False, indent=2), encoding="utf-8")
    chart_count = write_chart_files(results)
    print(f"已生成 GitHub Top100: {TARGET}")
    print(f"已生成 GitHub 选股逻辑: {LOGIC_TARGET}")
    print(f"已生成月线图表数据: {chart_count}")
    print(f"trade_date: {out.get('trade_date')}")
    print(f"model_version: {out.get('model_version')}")
    print(f"top100_count: {len(results)}")


if __name__ == "__main__":
    main()
