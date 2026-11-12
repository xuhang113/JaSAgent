#!/usr/bin/env bash
# 用 runsc 启动两台 agent。容器端口 8080，宿主机 18731 与 18732。没有 runsc 就失败，不退回裸 JVM。
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

bash "$JASAGENT_ROOT/scripts/sandbox/build-images.sh"
jasagent_require_runsc

if ! docker inspect "$JASAGENT_MYSQL_CONTAINER" >/dev/null 2>&1; then
  echo "找不到 MySQL 容器 ${JASAGENT_MYSQL_CONTAINER}。两台 agent 要对着同一份库，所以停在这里。" >&2
  exit 1
fi
mysql_ready=0
for _ in $(seq 1 30); do
  if docker inspect -f '{{.State.Running}}' "$JASAGENT_MYSQL_CONTAINER" 2>/dev/null | grep -q true; then
    mysql_ready=1
    break
  fi
  sleep 1
done
if [[ "$mysql_ready" -ne 1 ]]; then
  echo "MySQL 容器 ${JASAGENT_MYSQL_CONTAINER} 没有在运行。" >&2
  exit 1
fi
if ! jasagent_wait_mysql; then
  echo "MySQL 还不能接受查询。" >&2
  exit 1
fi
mysql_ip="$(jasagent_mysql_ip)"
if [[ -z "$mysql_ip" ]]; then
  echo "读不到 ${JASAGENT_MYSQL_CONTAINER} 在 bridge 上的地址。" >&2
  exit 1
fi
env_file="$JASAGENT_SANDBOX_DIR/agent.env"
jasagent_write_mysql_env "$env_file" "$mysql_ip"

# 宿主机 Java 会走系统 HTTPS 代理，runsc 里的 127.0.0.1 到不了那台代理。改走 host.docker.internal。
JASAGENT_HTTPS_PROXY_HOST=""
JASAGENT_HTTPS_PROXY_PORT=""
if command -v scutil >/dev/null 2>&1; then
  proxy_enabled="$(scutil --proxy | awk '/HTTPSEnable/ { print $3; exit }')"
  proxy_port="$(scutil --proxy | awk '/HTTPSPort/ { print $3; exit }')"
  if [[ "$proxy_enabled" == "1" && "$proxy_port" =~ ^[0-9]+$ ]]; then
    JASAGENT_HTTPS_PROXY_HOST="host.docker.internal"
    JASAGENT_HTTPS_PROXY_PORT="$proxy_port"
    echo "agent 的 HTTPS 经 host.docker.internal:${JASAGENT_HTTPS_PROXY_PORT} 出去。"
  fi
fi

start_one() {
  local name="$1"
  local host_port="$2"
  if docker inspect "$name" >/dev/null 2>&1; then
    docker rm -f "$name" >/dev/null
  fi
  if jasagent_port_busy "$host_port"; then
    echo "宿主机端口 ${host_port} 已被占用。" >&2
    exit 1
  fi
  docker run -d \
    --name "$name" \
    --runtime=runsc \
    --restart=no \
    --memory=1280m \
    --add-host=host.docker.internal:host-gateway \
    --env-file "$env_file" \
    -e "JASAGENT_HTTPS_PROXY_HOST=${JASAGENT_HTTPS_PROXY_HOST}" \
    -e "JASAGENT_HTTPS_PROXY_PORT=${JASAGENT_HTTPS_PROXY_PORT}" \
    -e JASAGENT_PORT=8080 \
    -p "127.0.0.1:${host_port}:8080" \
    "$JASAGENT_IMAGE_AGENT" >/dev/null
  local actual
  actual="$(docker inspect -f '{{.HostConfig.Runtime}}' "$name")"
  if [[ "$actual" != "runsc" ]]; then
    echo "未通过：${name} 实际运行时是 ${actual}。不会改用裸 JVM。" >&2
    docker rm -f "$name" >/dev/null || true
    exit 1
  fi
}

start_one jasagent-agent-1 18731
start_one jasagent-agent-2 18732

fail=0
for name in jasagent-agent-1 jasagent-agent-2; do
  if ! jasagent_wait_started "$name"; then
    echo "${name} 没有完成启动。最近日志：" >&2
    docker logs --tail 80 "$name" 2>&1 | sed -E 's/(PASSWORD=).*/\1[redacted]/' >&2 || true
    fail=1
  fi
done

echo "runtime:"
docker inspect --format '{{.Name}} runtime={{.HostConfig.Runtime}} status={{.State.Status}}' jasagent-agent-1 jasagent-agent-2
echo "proc:"
colima ssh -- ps -ef | grep -E 'runsc|jasagent-agent' | grep -v grep || true
echo "version:"
docker exec jasagent-agent-1 cat /proc/version || true
docker exec jasagent-agent-2 cat /proc/version || true
echo "health:"
curl -sS --max-time 5 -w '\n18731 http_code=%{http_code}\n' http://127.0.0.1:18731/health || true
echo
curl -sS --max-time 5 -w '\n18732 http_code=%{http_code}\n' http://127.0.0.1:18732/health || true
echo
if [[ "$fail" -ne 0 ]]; then
  exit 1
fi
echo "http://127.0.0.1:18731"
echo "http://127.0.0.1:18732"
