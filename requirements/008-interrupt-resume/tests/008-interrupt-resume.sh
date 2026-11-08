#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
mkdir -p "$HERE/../results" "$HERE/../logs"
cd "$ROOT"
set +e
mvn -pl jasagent-interrupt,jasagent-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=InterruptResumeTest,ResumeAfterRestartTest > "$HERE/../logs/008-interrupt-resume.log" 2>&1
code=$?
set -e
if [[ $code -eq 0 ]]; then
  echo PASS > "$HERE/../results/008-interrupt-resume.result"
else
  echo FAIL > "$HERE/../results/008-interrupt-resume.result"
fi
exit "$code"
