#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
mkdir -p "$HERE/../results" "$HERE/../logs"
cd "$ROOT"
set +e
mvn -pl jasagent-tool,jasagent-plugin,jasagent-agentpass -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ToolPassTest,PluginPassTest,DisabledPassGateTest > "$HERE/../logs/005-tool-plugin-agentpass.log" 2>&1
code=$?
set -e
if [[ $code -eq 0 ]]; then
  echo PASS > "$HERE/../results/005-tool-plugin-agentpass.result"
else
  echo FAIL > "$HERE/../results/005-tool-plugin-agentpass.result"
fi
exit "$code"
