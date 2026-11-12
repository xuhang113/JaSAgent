#!/usr/bin/env bash
# 在本机启动示例检索，监听 127.0.0.1:18751。不进 gVisor。
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"
# shellcheck disable=SC1091
source "$JASAGENT_ROOT/scripts/common.sh"
jasagent_require_java
jasagent_ensure_internal_token
if jasagent_port_busy 18751; then
  jasagent_stop_java_port 18751
fi
if jasagent_port_busy 18751; then
  echo "18751 已被占用，不启动 RAG。" >&2
  exit 1
fi
(
  cd "$JASAGENT_ROOT"
  mvn -pl jasagent-agentpass-rag -am -DskipTests package
)
jar="$(find "$JASAGENT_ROOT/jasagent-agentpass-rag/target" -maxdepth 1 -type f -name 'jasagent-agentpass-rag-*-exec.jar' | sort | tail -n 1)"
if [[ -z "$jar" ]]; then
  echo "找不到示例 RAG jar。" >&2
  exit 1
fi
jasagent_start_host_jar rag "$jar" "Started RagExampleApplication"
echo "http://127.0.0.1:18751"
