#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
mkdir -p "$HERE/../results" "$HERE/../logs"
cd "$ROOT"
set +e
mvn -pl jasagent-mcp -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=McpClientTest > "$HERE/../logs/010-mcp.log" 2>&1
code=$?
set -e
if [[ $code -eq 0 ]]; then
  echo PASS > "$HERE/../results/010-mcp.result"
else
  echo FAIL > "$HERE/../results/010-mcp.result"
fi
exit "$code"
