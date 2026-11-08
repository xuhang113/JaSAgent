#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
mkdir -p "$HERE/../results" "$HERE/../logs"
cd "$ROOT"
set +e
{
  mvn -pl jasagent-api -am install -DskipTests && mvn -pl jasagent-rag test -Dtest=RagPipelineTest
} > "$HERE/../logs/009-rag.log" 2>&1
code=$?
set -e
if [[ $code -eq 0 ]]; then
  echo PASS > "$HERE/../results/009-rag.result"
else
  echo FAIL > "$HERE/../results/009-rag.result"
fi
exit "$code"
