#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
mkdir -p "$HERE/../results" "$HERE/../logs"
cd "$ROOT"
set +e
mvn -pl jasagent-persist,jasagent-trace,jasagent-otel,jasagent-exception -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PersistProcessTest,ExecutionTraceTest,ExecutionOtelTest,AsyncExceptionTest > "$HERE/../logs/003-persist-trace-otel-exception.log" 2>&1
code=$?
if [[ $code -eq 0 ]]; then
  if grep -RIn --include=pom.xml "springframework" jasagent-persist jasagent-trace jasagent-otel jasagent-exception >> "$HERE/../logs/003-persist-trace-otel-exception.log"; then
    echo "spring dependency is not allowed" >> "$HERE/../logs/003-persist-trace-otel-exception.log"
    code=1
  fi
fi
set -e
if [[ $code -eq 0 ]]; then
  echo PASS > "$HERE/../results/003-persist-trace-otel-exception.result"
else
  echo FAIL > "$HERE/../results/003-persist-trace-otel-exception.result"
fi
exit "$code"
