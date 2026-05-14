#!/usr/bin/env python3
import json
import sys
from pathlib import Path

SOURCE = Path("outputs/mobile/latest_score.json")
TARGET = Path("docs/results/latest_score_top100.json")


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
    print(f"已生成 GitHub Top100: {TARGET}")
    print(f"trade_date: {out.get('trade_date')}")
    print(f"model_version: {out.get('model_version')}")
    print(f"top100_count: {len(results)}")


if __name__ == "__main__":
    main()
