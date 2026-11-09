#!/usr/bin/env bash
# 后台启动服务。已在运行时打印地址并退出 0。
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

host="${JASAGENT_HOST:-127.0.0.1}"
port="${JASAGENT_PORT:-18721}"
if [[ ! "$port" =~ ^[0-9]+$ ]] || (( port < 1 || port > 65535 )); then
  echo "端口必须是 1 到 65535 的数字。" >&2
  exit 1
fi

if jasagent_running_pid >/dev/null; then
  jasagent_print_address
  exit 0
fi

jasagent_require_java
jasagent_ensure_jar

mkdir -p "$JASAGENT_RUNTIME_DIR"
chmod 700 "$JASAGENT_RUNTIME_DIR"
url="http://${host}:${port}"
printf '%s\n' "$url" > "$JASAGENT_ADDRESS_FILE"
chmod 600 "$JASAGENT_ADDRESS_FILE"
: > "$JASAGENT_LOG_FILE"
chmod 600 "$JASAGENT_LOG_FILE"

jasagent_load_model_env
jasagent_load_checkpoint_env
# nohup 之后再 setsid，避免服务留在调用方的进程组里，脚本一退就被一起停掉。
pid="$(nohup python3 - "$JASAGENT_JAVA" "$JASAGENT_JAR" "$host" "$port" "$JASAGENT_LOG_FILE" <<'PY'
import os
import sys

java, jar, host, port, log = sys.argv[1:]
child = os.fork()
if child > 0:
    print(child)
    raise SystemExit(0)
os.setsid()
fd = os.open(log, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
os.dup2(fd, 1)
os.dup2(fd, 2)
if fd > 2:
    os.close(fd)
os.execv(java, [java, "--add-opens", "java.base/jdk.internal.loader=ALL-UNNAMED", "--add-opens", "java.base/java.net=ALL-UNNAMED", "-jar", jar, "server", "--server.address=" + host, "--server.port=" + port])
PY
)"
pid="$(printf '%s' "$pid" | tr -d '[:space:]')"
printf '%s\n' "$pid" > "$JASAGENT_PID_FILE"
chmod 600 "$JASAGENT_PID_FILE"

ready=0
n=0
while [[ "$n" -lt 150 ]]; do
  n=$((n + 1))
  if ! kill -0 "$pid" 2>/dev/null; then
    break
  fi
  if grep -q "Started JasagentApplication" "$JASAGENT_LOG_FILE" 2>/dev/null; then
    ready=1
    break
  fi
  if grep -q "APPLICATION FAILED TO START" "$JASAGENT_LOG_FILE" 2>/dev/null; then
    break
  fi
  sleep 0.2
done
if [[ "$ready" -ne 1 ]]; then
  if kill -0 "$pid" 2>/dev/null; then
    jasagent_stop_pid "$pid" || true
  fi
  echo "服务没有留在后台。日志：$JASAGENT_LOG_FILE" >&2
  rm -f "$JASAGENT_PID_FILE"
  exit 1
fi
printf '%s\n' "$url"
