#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
mkdir -p "$HERE/../results" "$HERE/../logs"
cd "$ROOT"
set +e
mvn -pl jasagent-llm -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=LlmRouterTest > "$HERE/../logs/002-llm-router.log" 2>&1
code=$?
set -e
if [[ $code -eq 0 ]]; then
  echo PASS > "$HERE/../results/002-llm-router.result"
else
  echo FAIL > "$HERE/../results/002-llm-router.result"
fi
exit "$code"
