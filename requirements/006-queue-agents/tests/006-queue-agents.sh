#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
mkdir -p "$HERE/../results" "$HERE/../logs"
cd "$ROOT"
set +e
mvn -pl jasagent-core -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=QueueAgentTest > "$HERE/../logs/006-queue-agents.log" 2>&1
code=$?
set -e
if [[ $code -eq 0 ]]; then
  echo PASS > "$HERE/../results/006-queue-agents.result"
else
  echo FAIL > "$HERE/../results/006-queue-agents.result"
fi
exit "$code"
