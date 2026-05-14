#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mkdir -p logs

python3 scripts/update_pg_daily_incremental.py
MAX_SCAN_COUNT="${MAX_SCAN_COUNT:-5000}" python3 scripts/pg_score_and_export.py
python3 scripts/export_github_top100.py

test -f outputs/mobile/latest_score.json
test -f outputs/mobile/mobile_result_pack.zip
test -f docs/results/latest_score_top100.json

ls -lh outputs/mobile/latest_score.json outputs/mobile/mobile_result_pack.zip docs/results/latest_score_top100.json

git add docs/results/latest_score_top100.json
if git diff --cached --quiet -- docs/results/latest_score_top100.json; then
    echo "GitHub Top100 无变化，无需提交。"
else
    TRADE_DATE="$(python3 - <<'PY'
import json
from pathlib import Path
p = Path("docs/results/latest_score_top100.json")
print(json.loads(p.read_text(encoding="utf-8")).get("trade_date", "unknown"))
PY
)"
    git commit -m "Update Top100 score results ${TRADE_DATE}" -- docs/results/latest_score_top100.json
    git push origin main
fi
