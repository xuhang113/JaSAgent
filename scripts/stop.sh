#!/usr/bin/env bash
# 按运行时目录里的进程号停止服务。没在运行时退出 0。
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

pid="$(jasagent_running_pid || true)"
if [[ -z "$pid" ]]; then
  exit 0
fi
if ! jasagent_stop_pid "$pid"; then
  echo "没能停掉进程 $pid。日志：$JASAGENT_LOG_FILE" >&2
  exit 1
fi
rm -f "$JASAGENT_PID_FILE"
exit 0
