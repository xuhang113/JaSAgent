#!/usr/bin/env bash
# 按运行时目录里的进程号停止服务。没在运行时退出 0。
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

stopped=""
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
while IFS= read -r pid; do
  jasagent_stop_recorded "$pid"
done < <(jasagent_repo_server_pids)
rm -f "$JASAGENT_PID_FILE"
exit 0
