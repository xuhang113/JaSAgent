#!/usr/bin/env bash
# 被 scripts/sandbox 下的启动脚本引用。口令只进用户主目录里的 env 文件，不进仓库。

if [[ -z "${JASAGENT_ROOT:-}" ]]; then
  JASAGENT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi

JASAGENT_IMAGE_AGENT="${JASAGENT_IMAGE_AGENT:-jasagent-agent:0.1.0}"
JASAGENT_IMAGE_GATEWAY="${JASAGENT_IMAGE_GATEWAY:-jasagent-gateway:0.1.0}"
JASAGENT_IMAGE_AGENTPASS="${JASAGENT_IMAGE_AGENTPASS:-jasagent-agentpass:0.1.0}"
JASAGENT_IMAGE_RAG="${JASAGENT_IMAGE_RAG:-jasagent-rag:0.1.0}"
JASAGENT_SANDBOX_DIR="${JASAGENT_SANDBOX_DIR:-${HOME:?需要 HOME}/.jasagent/sandbox}"
# 本机已有的 MySQL 容器。产品库名是 jasagent，不使用容器名当作库名。
JASAGENT_MYSQL_CONTAINER="${JASAGENT_MYSQL_CONTAINER:-urag-mysql}"

jasagent_docker_has_runsc() {
  docker info --format '{{json .Runtimes}}' 2>/dev/null | grep -q '"runsc"'
}

jasagent_require_runsc() {
  if jasagent_docker_has_runsc; then
    return 0
  fi
  echo "Docker 还没有 runsc，先在 Colima 虚拟机里登记。" >&2
  bash "$JASAGENT_ROOT/scripts/sandbox/install-runsc.sh"
  if ! jasagent_docker_has_runsc; then
    echo "未通过：Docker 运行时里没有 runsc。不会改用裸 JVM。" >&2
    exit 1
  fi
}

jasagent_mysql_ip() {
  docker inspect -f '{{if index .NetworkSettings.Networks "bridge"}}{{(index .NetworkSettings.Networks "bridge").IPAddress}}{{end}}' "$JASAGENT_MYSQL_CONTAINER"
}

jasagent_wait_mysql() {
  local file="${HOME}/.config/urag-mysql-root.password"
  local password n=0
  [[ -f "$file" ]] || return 1
  IFS= read -r password < "$file" || return 1
  password="${password%$'\r'}"
  [[ -n "$password" ]] || return 1
  while [[ "$n" -lt 60 ]]; do
    n=$((n + 1))
    if docker exec -e MYSQL_PWD="$password" "$JASAGENT_MYSQL_CONTAINER" \
      mysqladmin ping -uroot --protocol=TCP -h 127.0.0.1 --silent >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

jasagent_ensure_internal_token() {
  local file="$JASAGENT_SANDBOX_DIR/internal.token"
  local token
  mkdir -p "$JASAGENT_SANDBOX_DIR"
  chmod 700 "$JASAGENT_SANDBOX_DIR"
  if [[ ! -s "$file" ]]; then
    umask 077
    openssl rand -hex 32 > "$file"
    chmod 600 "$file"
  fi
  IFS= read -r token < "$file" || return 1
  token="${token%$'\r'}"
  if [[ -z "$token" ]]; then
    echo "内部令牌文件是空的。不会把令牌写进仓库。" >&2
    return 1
  fi
  export JASAGENT_INTERNAL_TOKEN="$token"
}

jasagent_load_xai_key() {
  local file="/Users/xuhang/Workspace/imagine-harness/.env"
  local line value first last
  if [[ -n "${XAI_API_KEY:-}" ]]; then
    return 0
  fi
  [[ -f "$file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    line="${line#"${line%%[![:space:]]*}"}"
    [[ "$line" == XAI_API_KEY=* ]] || continue
    value="${line#XAI_API_KEY=}"
    if [[ ${#value} -ge 2 ]]; then
      first="${value:0:1}"
      last="${value:$((${#value} - 1)):1}"
      if [[ "$first" == "$last" && ( "$first" == '"' || "$first" == "'" ) ]]; then
        value="${value:1:$((${#value} - 2))}"
      fi
    fi
    if [[ -n "$value" ]]; then
      export XAI_API_KEY="$value"
    fi
    return 0
  done < "$file"
  return 0
}

jasagent_write_mysql_env() {
  local dest="$1"
  local host="$2"
  local file="${HOME}/.config/urag-mysql-root.password"
  local password user url
  if [[ ! -f "$file" ]]; then
    echo "找不到 MySQL 口令文件 $file。不会把口令写进仓库。" >&2
    return 1
  fi
  IFS= read -r password < "$file" || return 1
  password="${password%$'\r'}"
  if [[ -z "$password" ]]; then
    echo "MySQL 口令文件是空的。" >&2
    return 1
  fi
  jasagent_ensure_internal_token
  jasagent_load_xai_key
  user="${JASAGENT_CHECKPOINT_JDBC_USER:-root}"
  url="jdbc:mysql://${host}:3306/jasagent?connectTimeout=10000&socketTimeout=15000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED"
  mkdir -p "$(dirname "$dest")"
  chmod 700 "$(dirname "$dest")"
  umask 077
  JASAGENT_ENV_DEST="$dest" \
  JASAGENT_ENV_URL="$url" \
  JASAGENT_ENV_USER="$user" \
  JASAGENT_ENV_PASSWORD="$password" \
  python3 - <<'PY'
import os
dest = os.environ["JASAGENT_ENV_DEST"]
lines = [
    "JASAGENT_CHECKPOINT=mysql",
    "JASAGENT_CHECKPOINT_JDBC_URL=" + os.environ["JASAGENT_ENV_URL"],
    "JASAGENT_CHECKPOINT_JDBC_USER=" + os.environ["JASAGENT_ENV_USER"],
    "JASAGENT_CHECKPOINT_JDBC_PASSWORD=" + os.environ["JASAGENT_ENV_PASSWORD"],
    "JASAGENT_JDBC_URL=" + os.environ["JASAGENT_ENV_URL"],
    "JASAGENT_JDBC_USER=" + os.environ["JASAGENT_ENV_USER"],
    "JASAGENT_JDBC_PASSWORD=" + os.environ["JASAGENT_ENV_PASSWORD"],
    "JASAGENT_INTERNAL_TOKEN=" + os.environ["JASAGENT_INTERNAL_TOKEN"],
    "JASAGENT_RAG_BASE_URL=http://host.docker.internal:18751",
    "JASAGENT_EGRESS_TICKET_URL=http://host.docker.internal:18741/v1/tickets",
]
key = os.environ.get("XAI_API_KEY", "")
if key:
    lines.append("XAI_API_KEY=" + key)
with open(dest, "w", encoding="utf-8") as handle:
    handle.write("\n".join(lines) + "\n")
os.chmod(dest, 0o600)
PY
  unset JASAGENT_ENV_DEST JASAGENT_ENV_URL JASAGENT_ENV_USER JASAGENT_ENV_PASSWORD
}

jasagent_stop_java_port() {
  local port="$1"
  local pid cmd
  [[ -n "$(command -v lsof || true)" ]] || return 0
  while read -r pid; do
    [[ "$pid" =~ ^[0-9]+$ ]] || continue
    cmd="$(ps -p "$pid" -o comm= 2>/dev/null || true)"
    if [[ "$cmd" == *java* ]]; then
      kill "$pid" 2>/dev/null || true
    fi
  done < <(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null || true)
  local _
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if ! jasagent_port_busy "$port"; then
      return 0
    fi
    sleep 0.2
  done
}

jasagent_wait_log() {
  local log="$1"
  local marker="$2"
  local pid="$3"
  local n=0
  while [[ "$n" -lt 180 ]]; do
    n=$((n + 1))
    if ! kill -0 "$pid" 2>/dev/null; then
      return 1
    fi
    if grep -q "$marker" "$log" 2>/dev/null; then
      return 0
    fi
    if grep -q "APPLICATION FAILED TO START" "$log" 2>/dev/null; then
      return 1
    fi
    sleep 1
  done
  return 1
}

jasagent_start_host_jar() {
  local name="$1"
  local jar="$2"
  local marker="$3"
  local log="$JASAGENT_SANDBOX_DIR/${name}.log"
  local pidfile="$JASAGENT_SANDBOX_DIR/${name}.pid"
  mkdir -p "$JASAGENT_SANDBOX_DIR"
  chmod 700 "$JASAGENT_SANDBOX_DIR"
  if [[ -f "$pidfile" ]]; then
    local old
    old="$(tr -d '[:space:]' < "$pidfile" || true)"
    if [[ "$old" =~ ^[0-9]+$ ]] && kill -0 "$old" 2>/dev/null; then
      kill "$old" 2>/dev/null || true
      sleep 1
    fi
  fi
  : > "$log"
  chmod 600 "$log"
  local java="${JASAGENT_JAVA:-java}"
  # setsid 之后进程不再跟调用方的进程组走，脚本结束也不会把它一起停掉。
  local pid
  pid="$(python3 - "$java" "$jar" "$log" <<'PY'
import os
import sys

java, jar, log = sys.argv[1:]
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
os.execv(java, [
    java,
    "--add-opens", "java.base/jdk.internal.loader=ALL-UNNAMED",
    "--add-opens", "java.base/java.net=ALL-UNNAMED",
    "-jar", jar,
])
PY
)"
  pid="$(printf '%s' "$pid" | tr -d '[:space:]')"
  printf '%s\n' "$pid" > "$pidfile"
  chmod 600 "$pidfile"
  if ! jasagent_wait_log "$log" "$marker" "$pid"; then
    echo "${name} 没有完成启动。日志：$log" >&2
    return 1
  fi
}

jasagent_wait_started() {
  local name="$1"
  local n=0
  while [[ "$n" -lt 180 ]]; do
    n=$((n + 1))
    if ! docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null | grep -q true; then
      return 1
    fi
    if docker logs "$name" 2>&1 | grep -q "Started JasagentApplication"; then
      return 0
    fi
    if docker logs "$name" 2>&1 | grep -q "APPLICATION FAILED TO START"; then
      return 1
    fi
    sleep 1
  done
  return 1
}

jasagent_port_busy() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    return 0
  fi
  return 1
}
