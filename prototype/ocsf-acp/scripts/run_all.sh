#!/usr/bin/env bash
# Reproduce the whole prototype proof from a clean checkout. NON-PRODUCTION.
# Usage: run_all.sh <work-dir> <steward-plans-dir>
#   <work-dir> receives upstream/ (pinned clones), dl/ (toolkit binary), build/ (compiled schemas), pkg/ (packages), neg/ (negatives)
#   <steward-plans-dir> is the private steward's plans/ directory holding the fixtures (not part of this repository).
set -euo pipefail
W="$1"; PL="$2"; HERE="$(cd "$(dirname "$0")" && pwd)"; P="$HERE/.."
mkdir -p "$W/upstream" "$W/dl/toolkit" "$W/build"
fetch() { local n=$1 sha=$2; if [ ! -d "$W/upstream/$n" ]; then git init -q "$W/upstream/$n"; git -C "$W/upstream/$n" remote add origin "https://github.com/ocsf/$n.git"; git -C "$W/upstream/$n" fetch -q --depth 1 origin "$sha"; git -C "$W/upstream/$n" checkout -q FETCH_HEAD; fi; }
fetch ocsf-schema 856d462bd20dc46cc1ffed2dfffe3b91ef0fbeba
fetch ocsf-schema-compiler 865887a9ee88580c471062f93da115e7599fa31d
TKDIR="$W/dl/toolkit"; TK="$TKDIR/ocsf-toolkit_v0.9.0_linux_amd64/ocsf-toolkit"
if [ ! -x "$TK" ]; then
  curl -sL -o "$TKDIR/ocsf-toolkit_v0.9.0_linux_amd64.tar.gz" https://github.com/ocsf/ocsf-toolkit/releases/download/v0.9.0/ocsf-toolkit_v0.9.0_linux_amd64.tar.gz
  echo "641fec9f8c457896c06ead9136fe8f4e50753cd95f8a68c24b25c2bcc9cfd5a5  $TKDIR/ocsf-toolkit_v0.9.0_linux_amd64.tar.gz" | sha256sum -c -
  tar xzf "$TKDIR/ocsf-toolkit_v0.9.0_linux_amd64.tar.gz" -C "$TKDIR"
fi
uv python install 3.14 >/dev/null
"$HERE/compile.sh" "$W/upstream/ocsf-schema" "$W/upstream/ocsf-schema-compiler" "$W/build/ext-901"
rm -rf "$W/ext-902"; cp -r "$P/extension" "$W/ext-902"
python3 -I -c "import json,sys; f=sys.argv[1]; d=json.load(open(f)); d['uid']=902; json.dump(d,open(f,'w'),indent=2)" "$W/ext-902/agentic_cli/extension.json"
"$HERE/compile.sh" "$W/upstream/ocsf-schema" "$W/upstream/ocsf-schema-compiler" "$W/build/ext-902" "$W/ext-902"
rm -rf "$W/pkg" "$W/neg"; mkdir -p "$W/neg"
python3 -I "$HERE/make_negatives.py" --steward-plans "$PL" --out "$W/neg"
python3 -I - "$P/specs" "$W/neg/fixtures" <<'EOF'
import json, sys
P, N = sys.argv[1], sys.argv[2]
def synth(base, name, path, mods):
    s = json.load(open(f'{P}/{base}.json')); s['run_uid'] = f'proto-{name}'
    s['synthetic'] = {'label': 'SYNTHETIC NEGATIVE VARIANT; not live evidence', 'derived_from': base, 'modifications': mods}
    s['sources'][0]['path'] = path
    if base != 'codex-pair-B': s['sources'] = [s['sources'][0]]
    json.dump(s, open(f'{P}/{name}.json', 'w'), indent=1); open(f'{P}/{name}.json', 'a').write('\n')
synth('claude-pair-A', 'S1-claude-stream-opaque', f'{N}/S1-claude-stream-opaque/stream.jsonl', ['duplicate-key line inserted at 3', 'non-UTF-8 line at 4', 'blank line at 5', 'CRLF on line 7'])
synth('codex-pair-B', 'S2-codex-two-exec', f'{N}/S2-codex-two-exec/rollout-1.jsonl', ['second custom_tool_call with identical input inserted after line 13'])
synth('antigravity-pair-A', 'S3-agy-two-run_command', f'{N}/S3-agy-two-run_command/stream.jsonl', ['second run_command step (index 7) inserted before the result line'])
EOF
for f in claude-pair-A codex-pair-B antigravity-pair-A S1-claude-stream-opaque S2-codex-two-exec S3-agy-two-run_command; do
  echo "=== $f"
  python3 -I "$HERE/adapt.py" --spec "$P/specs/$f.json" --compiled "$W/build/ext-901/compiled.json" --extension-dir "$P/extension/agentic_cli" --out "$W/pkg/$f" --steward-plans "$PL"
  "$HERE/validate.sh" "$TK" "$W/build/ext-901/compiled.json" "$W/pkg/$f" | tail -1
  python3 -I "$HERE/semantic_check.py" --pkg "$W/pkg/$f" --steward-plans "$PL"
  python3 -I "$HERE/measure.py" --pkg "$W/pkg/$f" --compiled "$W/build/ext-901/compiled.json" --extension-dir "$P/extension/agentic_cli" >/dev/null
  case $f in S*) ;; *) python3 -I "$HERE/queries.py" --pkg "$W/pkg/$f" --expected "$P/expected/$f.json";; esac
done
echo "=== reader pin (expect ACCEPTED then REFUSED)"
python3 -I "$HERE/reader_check.py" --pkg "$W/pkg/claude-pair-A" --compiled "$W/build/ext-901/compiled.json"
python3 -I "$HERE/reader_check.py" --pkg "$W/pkg/claude-pair-A" --compiled "$W/build/ext-902/compiled.json" || true
echo "=== mutations"
python3 -I "$HERE/make_negatives.py" --steward-plans "$PL" --out "$W/neg" --pkg-claude "$W/pkg/claude-pair-A" --pkg-codex "$W/pkg/codex-pair-B" --pkg-agy "$W/pkg/antigravity-pair-A" --pkg-codex-s2 "$W/pkg/S2-codex-two-exec"
for m in M1-time-as-string M2-unknown-attribute M3-missing-source_ref M4-type_uid-arithmetic M5-undefined-enum; do
  echo -n "$m (toolkit must reject): "; "$HERE/validate.sh" "$TK" "$W/build/ext-901/compiled.json" "$W/neg/mutations/$m" 2>&1 | grep -o 'error_events": [0-9]*\|validation_[a-z_]*' | sort -u | tr '\n' ' ' || true; echo
done
for m in W1-swap-identical-results:claude-pair-A W2-provisional-as-final:claude-pair-A W3-false-cost-availability:codex-pair-B W4-denial-attached-to-tool:antigravity-pair-A W5-ambiguous-attributed-by-order:S2-codex-two-exec; do
  n=${m%%:*}; base=${m##*:}; cp "$W/pkg/$base/run.json" "$W/neg/mutations/$n/run.json"
  echo -n "$n (toolkit passes, oracle must catch): "; "$HERE/validate.sh" "$TK" "$W/build/ext-901/compiled.json" "$W/neg/mutations/$n" 2>&1 | grep -o 'error_events": [0-9]*' | tr '\n' ' ' || true
  python3 -I "$HERE/semantic_check.py" --pkg "$W/neg/mutations/$n" --steward-plans "$PL" 2>&1 | grep -c "DEFECTIVE" | sed 's/^/ defective lines: /' || true
done
