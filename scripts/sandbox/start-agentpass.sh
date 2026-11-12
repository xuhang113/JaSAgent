#!/usr/bin/env bash
# 在本机启动通行证服务，监听 127.0.0.1:18741。不进 gVisor。
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"
# shellcheck disable=SC1091
source "$JASAGENT_ROOT/scripts/common.sh"
jasagent_require_java
jasagent_ensure_internal_token
if jasagent_port_busy 18741; then
  jasagent_stop_java_port 18741
fi
if jasagent_port_busy 18741; then
  echo "18741 已被占用，不启动 agentpass。" >&2
  exit 1
fi
(
  cd "$JASAGENT_ROOT"
  mvn -pl jasagent-agentpass-server -am -DskipTests package
)
jar="$(find "$JASAGENT_ROOT/jasagent-agentpass-server/target" -maxdepth 1 -type f -name 'jasagent-agentpass-server-*-exec.jar' | sort | tail -n 1)"
if [[ -z "$jar" ]]; then
  echo "找不到 agentpass jar。" >&2
  exit 1
fi
jasagent_start_host_jar agentpass "$jar" "Started AgentPassServerApplication"
echo "http://127.0.0.1:18741"
