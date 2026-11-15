#!/usr/bin/env bash
# 按运行时目录里的进程号停止服务。没在运行时退出 0。
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

stopped=""
# 按 pid 停掉一个已经记录的进程。同一个 pid 只停一次。
jasagent_stop_recorded() {
  local pid="$1"
  [[ -n "$pid" ]] || return 0
  case " $stopped " in
    *" $pid "*) return 0 ;;
  esac
  if ! jasagent_stop_pid "$pid"; then
    echo "没能停掉进程 $pid。日志：$JASAGENT_LOG_FILE" >&2
    exit 1
  fi
  stopped="$stopped $pid"
}

jasagent_stop_recorded "$(jasagent_running_pid || true)"
for file in "$JASAGENT_WEB_PID_FILE" "$JASAGENT_SIDECAR_PID_FILE" "$JASAGENT_AGENT_PRIMARY_PID_FILE" "$JASAGENT_AGENT_SECONDARY_PID_FILE"; do
  if [[ -f "$file" ]]; then
    jasagent_stop_recorded "$(tr -d '[:space:]' < "$file" || true)"
  fi
done
while IFS= read -r pid; do
  jasagent_stop_recorded "$pid"
done < <(jasagent_repo_server_pids)
rm -f "$JASAGENT_PID_FILE" "$JASAGENT_WEB_PID_FILE" "$JASAGENT_SIDECAR_PID_FILE" "$JASAGENT_AGENT_PRIMARY_PID_FILE" "$JASAGENT_AGENT_SECONDARY_PID_FILE"
# 只停本入口拉起的 Nacos。外部注册中心没有管理标记，这里不动。
jasagent_stop_managed_nacos
exit 0
