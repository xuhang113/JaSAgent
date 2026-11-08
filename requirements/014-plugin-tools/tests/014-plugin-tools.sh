#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
mkdir -p "$HERE/../results" "$HERE/../logs"
cd "$ROOT"
start=$(python3 -c 'import time; print(int(time.time()*1000))')
set +e
mvn -pl jasagent-plugin,jasagent-tool -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PluginToolTest,ExternalUsageTest > "$HERE/../logs/014-plugin-tools.log" 2>&1
code=$?
set -e
end=$(python3 -c 'import time; print(int(time.time()*1000))')
elapsed=$((end - start))
if [[ $code -eq 0 ]]; then
  status="通过"
else
  status="失败"
fi
printf '014\t%s\tPluginToolTest 与 ExternalUsageTest 全部通过\t%s\tlogs/014-plugin-tools.log\n' "$status" "$elapsed" > "$HERE/../results/014-plugin-tools.txt"
exit "$code"
