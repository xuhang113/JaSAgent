#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
mkdir -p "$HERE/../results" "$HERE/../logs"
cd "$ROOT"
set +e
mvn -pl jasagent-persist,jasagent-trace,jasagent-otel,jasagent-exception -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PersistStoreTest,FileTraceTest,OtelTracerTest,AsyncBoundaryTest > "$HERE/../logs/003-persist-trace-otel-exception.log" 2>&1
code=$?
set -e
if [[ $code -eq 0 ]]; then
  echo PASS > "$HERE/../results/003-persist-trace-otel-exception.result"
else
  echo FAIL > "$HERE/../results/003-persist-trace-otel-exception.result"
fi
exit "$code"
