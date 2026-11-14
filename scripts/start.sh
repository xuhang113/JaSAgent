#!/usr/bin/env bash
# 本机日常入口。JASAGENT_PORT 默认 18721 时拉起的是网关。
# 同时拉起可被反代的 jasagent-web 上游，以及 18731 和 18732 上的 agent。
# 网关就绪看 /health 里的 status=up，不看应用启动完成那一行日志。
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

host="${JASAGENT_HOST:-127.0.0.1}"
port="${JASAGENT_PORT:-18721}"
web_port="${JASAGENT_WEB_PORT:-18722}"
agent_primary=18731
agent_secondary=18732

if [[ ! "$port" =~ ^[0-9]+$ ]] || (( port < 1 || port > 65535 )); then
  echo "端口必须是 1 到 65535 的数字。" >&2
  exit 1
fi
if [[ ! "$web_port" =~ ^[0-9]+$ ]] || (( web_port < 1 || web_port > 65535 )); then
  echo "控制台上游端口必须是 1 到 65535 的数字。" >&2
  exit 1
fi

# 进程还在，且命令行包含给定片段时，认为这个 pid 文件对应的服务已启动。
jasagent_recorded_alive() {
  local file="$1"
  local needle="$2"
  local pid cmd
  [[ -f "$file" ]] || return 1
  pid="$(tr -d '[:space:]' < "$file")"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  cmd="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  [[ "$cmd" == *"$needle"* ]]
}

gateway_up=0
web_up=0
primary_up=0
secondary_up=0
if jasagent_recorded_alive "$JASAGENT_PID_FILE" "jasagent-gateway"; then
  gateway_up=1
fi
if jasagent_recorded_alive "$JASAGENT_WEB_PID_FILE" "jasagent-web"; then
  web_up=1
fi
if jasagent_recorded_alive "$JASAGENT_AGENT_PRIMARY_PID_FILE" "jasagent-app"; then
  primary_up=1
fi
if jasagent_recorded_alive "$JASAGENT_AGENT_SECONDARY_PID_FILE" "jasagent-app"; then
  secondary_up=1
fi
if [[ "$gateway_up" -eq 1 && "$web_up" -eq 1 && "$primary_up" -eq 1 && "$secondary_up" -eq 1 ]]; then
  jasagent_print_address
  exit 0
fi

jasagent_require_java
jasagent_ensure_stack_jars

mkdir -p "$JASAGENT_RUNTIME_DIR"
chmod 700 "$JASAGENT_RUNTIME_DIR"
url="http://${host}:${port}"
printf '%s\n' "$url" > "$JASAGENT_ADDRESS_FILE"
chmod 600 "$JASAGENT_ADDRESS_FILE"

jasagent_load_model_env
jasagent_load_checkpoint_env

started=()
# 本次新拉起的进程没有就绪时停掉，已经在跑的进程不动。
jasagent_fail() {
  local message="$1"
  local pid
  echo "$message" >&2
  for pid in "${started[@]+"${started[@]}"}"; do
    jasagent_stop_pid "$pid" || true
  done
  exit 1
}

# 端口被别的进程占用时停掉本次新拉起的进程并退出，避免和已有服务抢同一套端口。
jasagent_require_free() {
  local label="$1"
  local listen_port="$2"
  if jasagent_port_open "$host" "$listen_port"; then
    jasagent_fail "${label} 端口 ${listen_port} 已被占用。"
  fi
}

if [[ "$web_up" -ne 1 ]]; then
  jasagent_require_free "控制台上游" "$web_port"
  web_pid="$(jasagent_spawn_jar "$JASAGENT_WEB_JAR" "$JASAGENT_WEB_LOG_FILE" "--server.address=${host}" "--server.port=${web_port}")"
  jasagent_write_pid "$JASAGENT_WEB_PID_FILE" "$web_pid"
  started+=("$web_pid")
  if ! jasagent_wait_port_open "$host" "$web_port" "$web_pid"; then
    jasagent_fail "控制台上游没有留在后台。日志：$JASAGENT_WEB_LOG_FILE"
  fi
fi

if [[ "$gateway_up" -ne 1 ]]; then
  jasagent_require_free "网关" "$port"
  gateway_pid="$(jasagent_spawn_jar "$JASAGENT_GATEWAY_JAR" "$JASAGENT_LOG_FILE" \
    "--server.address=${host}" \
    "--server.port=${port}" \
    "--jasagent.gateway.web-origin=http://${host}:${web_port}")"
  jasagent_write_pid "$JASAGENT_PID_FILE" "$gateway_pid"
  started+=("$gateway_pid")
  if ! jasagent_wait_http_up "${url}/health" "$gateway_pid"; then
    jasagent_fail "网关没有留在后台。日志：$JASAGENT_LOG_FILE"
  fi
fi

jasagent_start_agent() {
  local listen_port="$1"
  local pid_file="$2"
  local log_file="$3"
  jasagent_require_free "agent" "$listen_port"
  local agent_pid
  agent_pid="$(jasagent_spawn_jar "$JASAGENT_APP_JAR" "$log_file" server "--server.address=${host}" "--server.port=${listen_port}")"
  jasagent_write_pid "$pid_file" "$agent_pid"
  started+=("$agent_pid")
  if ! jasagent_wait_port_open "$host" "$listen_port" "$agent_pid"; then
    jasagent_fail "agent ${listen_port} 没有留在后台。日志：$log_file"
  fi
}

if [[ "$primary_up" -ne 1 ]]; then
  jasagent_start_agent "$agent_primary" "$JASAGENT_AGENT_PRIMARY_PID_FILE" "$JASAGENT_AGENT_PRIMARY_LOG_FILE"
fi
if [[ "$secondary_up" -ne 1 ]]; then
  jasagent_start_agent "$agent_secondary" "$JASAGENT_AGENT_SECONDARY_PID_FILE" "$JASAGENT_AGENT_SECONDARY_LOG_FILE"
fi

printf '%s\n' "$url"
