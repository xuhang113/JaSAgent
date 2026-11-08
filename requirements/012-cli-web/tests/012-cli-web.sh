#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
mkdir -p "$HERE/../results" "$HERE/../logs"
cd "$ROOT"
set +e
mvn -pl jasagent-app,jasagent-cli -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WebFlowTest,CliCommandTest,ConsolePageTest > "$HERE/../logs/012-cli-web.log" 2>&1
code=$?
set -e
if [[ $code -eq 0 ]]; then
  echo PASS > "$HERE/../results/012-cli-web.result"
else
  echo FAIL > "$HERE/../results/012-cli-web.result"
fi
exit "$code"
