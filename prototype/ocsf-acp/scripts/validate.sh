#!/usr/bin/env bash
# Validate every event file of a package with ocsf-toolkit v0.9.0 against a compiled schema.
# Usage: validate.sh <ocsf-toolkit-binary> <compiled.json> <package-dir> [summary-out.json]
# The toolkit validates one JSON object per file; adapt.py writes <package-dir>/events/NNNN.json beside
# events.jsonl for this purpose (JSONL input is not supported by v0.9.0).
set -uo pipefail
TK="$1"; SCHEMA="$2"; PKG="$3"; OUT="${4:-$PKG/validation-summary.json}"
REPORTS="$(mktemp -d)"
"$TK" --schema "$SCHEMA" --events-dir "$PKG/events" --validate --fail-on-validation-errors \
  --output-dir "$REPORTS" --summary "$OUT" --summary-format json --overwrite > "$PKG/validation.stdout" 2> "$PKG/validation.stderr"
rc=$?
python3 -I - "$OUT" "$REPORTS/reports" <<'EOF'
import json, os, sys
s = json.load(open(sys.argv[1]))
# per-file findings (errors) for a compact view
errs = {}
for root, _, files in os.walk(sys.argv[2]):
    for f in sorted(files):
        r = json.load(open(os.path.join(root, f)))
        fl = [x for x in r.get("validation", {}).get("findings", []) if x.get("level") == "error"] if isinstance(r, dict) else []
        if fl: errs[f] = [(x.get("code"), x.get("message")) for x in fl]
print(json.dumps({"summary": s, "files_with_errors": len(errs)}, indent=None)[:1500])
for f, e in list(errs.items())[:20]:
    print(f, e[:3])
EOF
echo "toolkit exit=$rc"
exit $rc
