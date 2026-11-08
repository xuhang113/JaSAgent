#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
mkdir -p "$HERE/../results" "$HERE/../logs"
cd "$ROOT"
set +e
mvn -pl jasagent-evaluation -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=EvaluationTest > "$HERE/../logs/011-evaluation.log" 2>&1
code=$?
set -e
if [[ $code -eq 0 ]]; then
  echo PASS > "$HERE/../results/011-evaluation.result"
else
  echo FAIL > "$HERE/../results/011-evaluation.result"
fi
exit "$code"
