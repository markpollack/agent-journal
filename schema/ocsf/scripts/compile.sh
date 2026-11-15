#!/usr/bin/env bash
# Compile OCSF core 1.9.0 plus the agentic_cli extension with the pinned ocsf-schema-compiler.
# Usage: compile.sh <ocsf-schema-dir> <ocsf-schema-compiler-dir> <out-dir> [extension-dir-override]
# Requires: uv (installs CPython 3.14 on demand). Produces <out-dir>/compiled.json and <out-dir>/compile.log.
set -euo pipefail
SCHEMA_DIR="$1"; COMPILER_DIR="$2"; OUT_DIR="$3"
HERE="$(cd "$(dirname "$0")" && pwd)"
EXT_PARENT="${4:-$HERE/../extension}"
mkdir -p "$OUT_DIR"
( cd "$COMPILER_DIR" && uv run --python 3.14 --no-project env PYTHONPATH=src \
    python -m ocsf_schema_compiler --extensions-path "$EXT_PARENT" "$SCHEMA_DIR" ) \
  > "$OUT_DIR/compiled.json" 2> "$OUT_DIR/compile.log"
sha256sum "$OUT_DIR/compiled.json" | awk '{print $1}' > "$OUT_DIR/compiled.sha256"
grep -E "INFO: Compile completed|ERROR|WARNING" "$OUT_DIR/compile.log" || true
echo "compiled.json sha256 $(cat "$OUT_DIR/compiled.sha256")"
