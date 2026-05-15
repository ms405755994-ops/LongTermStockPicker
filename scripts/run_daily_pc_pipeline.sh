#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mkdir -p logs

load_env_value() {
    local key="$1"
    if [[ ! -f .env ]]; then
        return 0
    fi
    python3 - "$key" <<'PY'
from pathlib import Path
import sys

key = sys.argv[1]
for raw in Path(".env").read_text(encoding="utf-8", errors="ignore").splitlines():
    line = raw.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    if k.strip() == key:
        print(v.strip().strip('"').strip("'"))
        break
PY
}

push_origin_main() {
    local token="${GITHUB_TOKEN:-}"
    if [[ -z "$token" ]]; then
        token="$(load_env_value GITHUB_TOKEN)"
    fi
    if [[ -z "$token" ]]; then
        git push origin main
        return
    fi

    local askpass
    askpass="$(mktemp)"
    cat > "$askpass" <<'EOF'
#!/usr/bin/env bash
case "$1" in
    *Username*) printf '%s\n' "x-access-token" ;;
    *Password*) printf '%s\n' "$GITHUB_TOKEN" ;;
    *) printf '%s\n' "" ;;
esac
EOF
    chmod 700 "$askpass"
    GITHUB_TOKEN="$token" GIT_ASKPASS="$askpass" GIT_TERMINAL_PROMPT=0 git push origin main
    rm -f "$askpass"
}

python3 scripts/update_pg_daily_incremental.py
python3 scripts/update_pg_adj_factor_incremental.py
MAX_SCAN_COUNT="${MAX_SCAN_COUNT:-5000}" python3 scripts/pg_score_and_export.py
MAX_FINANCIAL_COUNT="${MAX_FINANCIAL_COUNT:-100}" python3 scripts/backfill_pg_financial_snapshot.py
MAX_SCAN_COUNT="${MAX_SCAN_COUNT:-5000}" python3 scripts/pg_score_and_export.py
FINANCIAL_MISSING_ONLY=1 MAX_FINANCIAL_COUNT="${MAX_FINANCIAL_COUNT:-100}" python3 scripts/backfill_pg_financial_snapshot.py
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
    push_origin_main
fi
