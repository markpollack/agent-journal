#!/usr/bin/env bash
# Validate the execution records of every committed fixture core (and each child core) with the pinned ocsf-toolkit
# against the pinned compiled schema, and write one committed summary per core tied to the event file's sha256.
#   validate-fixtures.sh <ocsf-toolkit-binary> <compiled.json> [fixtures-dir] [summaries-out-dir]
# Record rule (declared in every file's format record): a line whose top-level `declaration` member is an object is a
# declaration record, validated by the Java reader; every other line is an OCSF execution event and goes to the
# upstream validator (one JSON object per file, as v0.9.0 requires). RequirementsAccountingIT reads the summaries and
# establishes UC5-AC11 only when a summary's recorded hash equals the current event file and it reports no error.
set -uo pipefail
TK="$1"; SCHEMA="$2"
HERE="$(cd "$(dirname "$0")" && pwd)"
FIXTURES="${3:-$HERE/../../../journal-capture-tck/src/test/resources/ocsf/fixtures}"
OUT="${4:-$HERE/../../../journal-capture-tck/src/test/resources/ocsf/validation}"
mkdir -p "$OUT"
SCHEMA_SHA=$(sha256sum "$SCHEMA" | awk '{print $1}')
TK_SHA=$(sha256sum "$TK" | awk '{print $1}')
WORK="$(mktemp -d)"
status=0
for events in $(find "$FIXTURES" -path '*/package/*' -name events.jsonl | sort); do
  rel="${events#$FIXTURES/}"                                   # <fixture>/package[/children/<id>]/events.jsonl
  fixture="${rel%%/*}"
  core="${rel#*/package}"; core="${core%/events.jsonl}"; core="${core#/}"   # "" or children/<id>
  id="$fixture$( [ -n "$core" ] && echo "_$(echo "$core" | tr '/' '_')" )"
  dir="$WORK/$id"; mkdir -p "$dir/events"
  python3 -I - "$events" "$dir/events" <<'EOF'
import json, sys
n = 0; skipped = 0
for line in open(sys.argv[1], encoding='utf-8'):
    line = line.rstrip('\n')
    if not line.strip():
        continue
    o = json.loads(line)
    if isinstance(o.get('declaration'), dict):
        skipped += 1
        continue
    n += 1
    open('%s/%04d.json' % (sys.argv[2], n), 'w', encoding='utf-8').write(line + '\n')
print(n, skipped)
EOF
  counts=$(python3 -I - "$events" <<'EOF'
import json, sys
n = s = 0
for line in open(sys.argv[1], encoding='utf-8'):
    if not line.strip(): continue
    o = json.loads(line)
    if isinstance(o.get('declaration'), dict): s += 1
    else: n += 1
print(n, s)
EOF
)
  "$TK" --schema "$SCHEMA" --events-dir "$dir/events" --validate --fail-on-validation-errors \
    --output-dir "$dir/reports" --summary "$dir/summary.json" --summary-format json --overwrite > "$dir/stdout" 2> "$dir/stderr"
  rc=$?
  python3 -I - "$dir/summary.json" "$dir/reports/reports" "$events" "$OUT/$id.json" "$fixture" "$core" "$SCHEMA_SHA" "$TK_SHA" "$rc" "$counts" <<'EOF'
import hashlib, json, os, sys
summary = json.load(open(sys.argv[1]))
findings = {}
for root, _, files in os.walk(sys.argv[2]):
    for f in sorted(files):
        r = json.load(open(os.path.join(root, f)))
        fl = r.get("validation", {}).get("findings", []) if isinstance(r, dict) else []
        if fl:
            findings[f] = [{"level": x.get("level"), "code": x.get("code"), "message": (x.get("message") or "")[:200]} for x in fl]
events_sha = hashlib.sha256(open(sys.argv[3], 'rb').read()).hexdigest()
n, s = sys.argv[10].split()
out = {
    "kind": "agent-journal/upstream-validation-summary", "version": "0.1",
    "fixture": sys.argv[5], "core": sys.argv[6] or "main", "events_jsonl": os.path.relpath(sys.argv[3], os.path.dirname(os.path.dirname(sys.argv[4]))),
    "events_jsonl_sha256": events_sha, "execution_records": int(n), "declarations_skipped": int(s),
    "record_rule": "a line whose top-level 'declaration' member is an object is a declaration (validated by the Java reader); every other line is an OCSF execution event (validated here)",
    "toolkit": {"name": "ocsf-toolkit", "release": "v0.9.0", "binary_sha256": sys.argv[8], "exit_code": int(sys.argv[9])},
    "compiled_schema_sha256": sys.argv[7],
    "result": {"event_files_processed": summary.get("event_files_processed"), "error_events": summary.get("validation", {}).get("error_events"),
               "warning_only_events": summary.get("validation", {}).get("warning_only_events"), "issues_reported": summary.get("issues", {}).get("reported_count")},
    "findings": findings,
    "note": "warning-only findings are the toolkit's enum-sibling notice on tool_kind 'other' (a known v0.9.0 limit recorded in the lock); any error event fails UC5-AC11 for this core",
}
json.dump(out, open(sys.argv[4], 'w'), indent=1)
open(sys.argv[4], 'a').write('\n')
print("%-70s events %3s errors %s warnings %s exit %s" % (sys.argv[5] + (" " + sys.argv[6] if sys.argv[6] else ""), n, out["result"]["error_events"], out["result"]["warning_only_events"], sys.argv[9]))
EOF
  [ $rc -ne 0 ] && status=$rc
done
echo "toolkit $TK_SHA schema $SCHEMA_SHA"
exit $status
