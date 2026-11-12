#!/usr/bin/env bash
# 在本机启动网关，监听 127.0.0.1:18721。网关要读本机 MySQL 口令文件，所以不放进容器。
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"
# shellcheck disable=SC1091
source "$JASAGENT_ROOT/scripts/common.sh"
jasagent_require_java
jasagent_ensure_internal_token
jasagent_load_xai_key
jasagent_stop_java_port 18721
if jasagent_port_busy 18721; then
  echo "18721 仍被非 Java 进程占用，不启动网关。" >&2
  exit 1
fi
(
  cd "$JASAGENT_ROOT"
  mvn -pl jasagent-gateway -am -DskipTests package
)
jar="$(find "$JASAGENT_ROOT/jasagent-gateway/target" -maxdepth 1 -type f -name 'jasagent-gateway-*.jar' ! -name '*.jar.original' | sort | tail -n 1)"
if [[ -z "$jar" ]]; then
  echo "找不到网关 jar。" >&2
  exit 1
fi
jasagent_start_host_jar gateway "$jar" "Started GatewayApplication"
echo "http://127.0.0.1:18721"
