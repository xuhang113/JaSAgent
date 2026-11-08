#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
mkdir -p "$HERE/../results" "$HERE/../logs"
cd "$ROOT"
set +e
mvn -pl jasagent-auth -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=LoginDirectoryTest > "$HERE/../logs/001-login-directories.log" 2>&1
code=$?
set -e
if [[ $code -eq 0 ]]; then
  echo PASS > "$HERE/../results/001-login-directories.result"
else
  echo FAIL > "$HERE/../results/001-login-directories.result"
fi
exit "$code"
