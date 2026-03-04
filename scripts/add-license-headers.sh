#!/usr/bin/env bash
# Adds BSL 1.1 license headers to all Java source files that don't already have one.
# Also replaces .tuvium references with .agent-journal in comments/javadoc.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

HEADER='/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */
'

# Add license header to Java files that don't have one
find "$REPO_ROOT" -name '*.java' -not -path '*/target/*' | while read -r file; do
    if ! head -1 "$file" | grep -q '^/\*'; then
        # File doesn't start with a block comment — prepend header
        tmp=$(mktemp)
        printf '%s\n' "$HEADER" | cat - "$file" > "$tmp"
        mv "$tmp" "$file"
        echo "Added header: $file"
    else
        echo "Skipped (has header): $file"
    fi
done

# Replace .tuvium with .agent-journal in all Java files (comments/javadoc/code)
find "$REPO_ROOT" -name '*.java' -not -path '*/target/*' -exec \
    sed -i 's/\.tuvium/.agent-journal/g' {} +

# Replace tuvium-runtime-core with agent-journal in comments
find "$REPO_ROOT" -name '*.java' -not -path '*/target/*' -exec \
    sed -i 's/tuvium-runtime-core/agent-journal/g' {} +

echo ""
echo "Done. Review changes with: git diff"
