#!/usr/bin/env bash
# 被 scripts/start.sh、scripts/stop.sh 和仓库根的 jasagent 引用。
# 运行时文件放在用户主目录下，不写进仓库，也不写死用户名。

if [[ -z "${JASAGENT_ROOT:-}" ]]; then
  JASAGENT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fi

if [[ -z "${HOME:-}" ]]; then
  echo "需要 HOME 来确定运行时目录。" >&2
  exit 1
fi

JASAGENT_RUNTIME_DIR="${JASAGENT_RUNTIME_DIR:-$HOME/.jasagent/runtime}"
JASAGENT_PID_FILE="$JASAGENT_RUNTIME_DIR/server.pid"
JASAGENT_LOG_FILE="$JASAGENT_RUNTIME_DIR/server.log"
JASAGENT_ADDRESS_FILE="$JASAGENT_RUNTIME_DIR/address"
JASAGENT_WEB_PID_FILE="$JASAGENT_RUNTIME_DIR/web.pid"
JASAGENT_WEB_LOG_FILE="$JASAGENT_RUNTIME_DIR/web.log"
JASAGENT_AGENT_PRIMARY_PID_FILE="$JASAGENT_RUNTIME_DIR/agent-18731.pid"
JASAGENT_AGENT_PRIMARY_LOG_FILE="$JASAGENT_RUNTIME_DIR/agent-18731.log"
JASAGENT_AGENT_SECONDARY_PID_FILE="$JASAGENT_RUNTIME_DIR/agent-18732.pid"
JASAGENT_AGENT_SECONDARY_LOG_FILE="$JASAGENT_RUNTIME_DIR/agent-18732.log"
# 本进程拉起的 Nacos 才记在这里。连上已有的 Nacos 时不写，停止时也不会把它关掉。
JASAGENT_NACOS_MANAGED_FILE="$JASAGENT_RUNTIME_DIR/nacos.managed"
# Nacos 启动日志只放在运行时目录。脚本不把日志内容打到终端。
JASAGENT_NACOS_LOG_FILE="$JASAGENT_RUNTIME_DIR/nacos.log"

jasagent_read_pid() {
  local pid
  [[ -f "$JASAGENT_PID_FILE" ]] || return 1
  pid="$(tr -d '[:space:]' < "$JASAGENT_PID_FILE")"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$pid"
}

# 网关进程。日常入口的 server.pid 只认这个，避免把 agent 当成 18721 上的服务。
jasagent_is_gateway_command() {
  local cmd="$1"
  [[ "$cmd" == *jasagent-gateway-*.jar* || "$cmd" == *dev.jasagent.gateway.GatewayApplication* ]]
}

# 本机入口拉起的网关、控制台上游和 agent。停止时按这些命令行识别。
jasagent_is_server_command() {
  local cmd="$1"
  jasagent_is_gateway_command "$cmd" \
    || [[ "$cmd" == *jasagent-app-*.jar* || "$cmd" == *jasagent-web-*-exec.jar* || "$cmd" == *dev.jasagent.app.JasagentApplication* || "$cmd" == *dev.jasagent.web.upstream.WebUpstreamApplication* ]]
}

jasagent_running_pid() {
  local pid cmd
  pid="$(jasagent_read_pid)" || return 1
  if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$JASAGENT_PID_FILE"
    return 1
  fi
  cmd="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  if jasagent_is_gateway_command "$cmd"; then
    printf '%s\n' "$pid"
    return 0
  fi
  rm -f "$JASAGENT_PID_FILE"
  return 1
}

jasagent_print_address() {
  local line=""
  if [[ -f "$JASAGENT_ADDRESS_FILE" ]]; then
    line="$(head -n 1 "$JASAGENT_ADDRESS_FILE" | tr -d '\r')"
  fi
  if [[ -z "$line" ]]; then
    line="http://127.0.0.1:18721"
  fi
  printf '%s\n' "$line"
}

jasagent_describe_java() {
  local bin="$1"
  local raw quoted major
  [[ -n "$bin" && -x "$bin" ]] || return 1
  raw="$("$bin" -version 2>&1 | head -n 1 || true)"
  quoted=""
  if [[ "$raw" == *\"*\"* ]]; then
    quoted="${raw#*\"}"
    quoted="${quoted%%\"*}"
  fi
  major="${quoted%%.*}"
  if [[ "$major" == "1" ]]; then
    major="$(printf '%s' "$quoted" | cut -d. -f2)"
  fi
  [[ "$major" =~ ^[0-9]+$ ]] || return 1
  printf '%s %s\n' "$major" "$quoted"
}

jasagent_use_java_if_ready() {
  local bin="$1"
  local described major
  described="$(jasagent_describe_java "$bin" 2>/dev/null || true)"
  major="${described%% *}"
  [[ "$major" =~ ^[0-9]+$ ]] || return 1
  if (( major < 21 )); then
    return 1
  fi
  JASAGENT_JAVA="$bin"
  JASAGENT_JAVA_VERSION="${described#* }"
  return 0
}

jasagent_note_java_fallback() {
  if [[ -n "${JASAGENT_TOO_OLD_JAVA:-}" ]]; then
    echo "当前 java 是 ${JASAGENT_TOO_OLD_VERSION:-未知}（${JASAGENT_TOO_OLD_JAVA}），不能运行。改用本机 Java ${JASAGENT_JAVA_VERSION}（${JASAGENT_JAVA}）。" >&2
  fi
}

jasagent_realpath() {
  local path="$1"
  local dir
  while [[ -L "$path" ]]; do
    dir="$(cd "$(dirname "$path")" && pwd)"
    path="$(readlink "$path")"
    if [[ "$path" != /* ]]; then
      path="$dir/$path"
    fi
  done
  printf '%s\n' "$path"
}

jasagent_export_java_home() {
  local resolved home
  resolved="$(jasagent_realpath "$JASAGENT_JAVA")"
  home="$(cd "$(dirname "$resolved")/.." && pwd)"
  if [[ -x "$home/bin/java" && -e "$home/release" ]]; then
    export JAVA_HOME="$home"
    export PATH="$home/bin:$PATH"
  fi
}

jasagent_require_java() {
  local version prefix candidate primary described major
  JASAGENT_TOO_OLD_JAVA=""
  JASAGENT_TOO_OLD_VERSION=""
  primary="$(command -v java 2>/dev/null || true)"
  if [[ -z "$primary" && -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    primary="${JAVA_HOME}/bin/java"
  fi
  if [[ -n "$primary" ]]; then
    described="$(jasagent_describe_java "$primary" 2>/dev/null || true)"
    major="${described%% *}"
    if [[ "$major" =~ ^[0-9]+$ ]] && (( major >= 21 )); then
      JASAGENT_JAVA="$primary"
      JASAGENT_JAVA_VERSION="${described#* }"
      jasagent_export_java_home
      return 0
    fi
    if [[ "$major" =~ ^[0-9]+$ ]]; then
      JASAGENT_TOO_OLD_JAVA="$primary"
      JASAGENT_TOO_OLD_VERSION="${described#* }"
    fi
  fi
  if [[ -n "${JAVA_HOME:-}" ]] && jasagent_use_java_if_ready "${JAVA_HOME}/bin/java"; then
    jasagent_note_java_fallback
    jasagent_export_java_home
    return 0
  fi
  if [[ -x /usr/libexec/java_home ]]; then
    for version in 21 22 23 24 25; do
      candidate="$(/usr/libexec/java_home -v "$version" 2>/dev/null || true)"
      if [[ -n "$candidate" ]] && jasagent_use_java_if_ready "$candidate/bin/java"; then
        jasagent_note_java_fallback
        jasagent_export_java_home
        return 0
      fi
    done
  fi
  for prefix in /opt/homebrew/opt /usr/local/opt; do
    for version in openjdk@21 openjdk@22 openjdk@23 openjdk@24 openjdk@25 openjdk; do
      if jasagent_use_java_if_ready "$prefix/$version/bin/java"; then
        jasagent_note_java_fallback
        jasagent_export_java_home
        return 0
      fi
    done
  done
  if [[ -n "${JASAGENT_TOO_OLD_JAVA:-}" ]]; then
    echo "需要 Java 21 或更高。当前 java 是 ${JASAGENT_TOO_OLD_VERSION:-未知}（${JASAGENT_TOO_OLD_JAVA}）。不会改用 Java 17 或更低版本。" >&2
  else
    echo "需要 Java 21 或更高。当前找不到可用的 java。不会改用 Java 17 或更低版本。" >&2
  fi
  exit 1
}

jasagent_sources_newer_than() {
  local jar="$1"
  local path
  while IFS= read -r path; do
    if [[ -n "$path" ]]; then
      return 0
    fi
  done < <(find \
    "$JASAGENT_ROOT/pom.xml" \
    "$JASAGENT_ROOT"/jasagent-*/pom.xml \
    "$JASAGENT_ROOT"/jasagent-*/src/main \
    "$JASAGENT_ROOT/jasagent-web/frontend/src" \
    "$JASAGENT_ROOT/jasagent-web/frontend/index.html" \
    "$JASAGENT_ROOT/jasagent-web/frontend/package.json" \
    "$JASAGENT_ROOT/jasagent-web/frontend/package-lock.json" \
    "$JASAGENT_ROOT/jasagent-web/frontend/vite.config.js" \
    "$JASAGENT_ROOT/jasagent-web/frontend/build.sh" \
    -type f -newer "$jar" 2>/dev/null || true)
  return 1
}

jasagent_repo_server_pids() {
  local line pid cmd
  while IFS= read -r line; do
    line="${line#"${line%%[![:space:]]*}"}"
    [[ -n "$line" ]] || continue
    pid="${line%% *}"
    cmd="${line#"$pid"}"
    cmd="${cmd#"${cmd%%[![:space:]]*}"}"
    [[ "$pid" =~ ^[0-9]+$ ]] || continue
    if [[ "$cmd" == *"$JASAGENT_ROOT"/jasagent-app/target/jasagent-app-*.jar* \
      || "$cmd" == *"$JASAGENT_ROOT"/jasagent-gateway/target/jasagent-gateway-*.jar* \
      || "$cmd" == *"$JASAGENT_ROOT"/jasagent-web/target/jasagent-web-*-exec.jar* ]]; then
      printf '%s\n' "$pid"
    fi
  done < <(ps -axww -o pid=,command= 2>/dev/null || true)
}

jasagent_find_jar() {
  local dir="$JASAGENT_ROOT/jasagent-app/target"
  [[ -d "$dir" ]] || return 1
  find "$dir" -maxdepth 1 -type f -name 'jasagent-app-*.jar' ! -name '*.jar.original' | sort | tail -n 1
}

jasagent_ensure_jar() {
  local jar=""
  jar="$(jasagent_find_jar || true)"
  if [[ -z "$jar" ]] || jasagent_sources_newer_than "$jar"; then
    if ! command -v mvn >/dev/null 2>&1; then
      echo "需要先打包，但找不到 mvn。请安装 Maven 后在仓库根执行：mvn -pl jasagent-app -am -DskipTests package" >&2
      exit 1
    fi
    (cd "$JASAGENT_ROOT" && mvn -pl jasagent-app -am -DskipTests package)
    jar="$(jasagent_find_jar || true)"
  fi
  if [[ -z "$jar" || ! -f "$jar" ]]; then
    echo "找不到 jasagent-app 的可执行 jar。" >&2
    exit 1
  fi
  JASAGENT_JAR="$jar"
}

jasagent_section_is_gateway() {
  local name
  name="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -s '[:space:]' ' ')"
  name="${name# }"
  name="${name% }"
  [[ "$name" == "gateway" || "$name" == "ai gateway" || "$name" == "ai-gateway" ]]
}

jasagent_load_checkpoint_env() {
  if [[ "${JASAGENT_CHECKPOINT:-}" == "sqlite" || "${JASAGENT_CHECKPOINT:-}" == "postgres" ]]; then
    return 0
  fi
  if [[ -n "${JASAGENT_CHECKPOINT_JDBC_URL:-}" ]]; then
    export JASAGENT_CHECKPOINT="${JASAGENT_CHECKPOINT:-mysql}"
    return 0
  fi
  local file password
  file="${HOME}/.config/urag-mysql-root.password"
  if [[ ! -f "$file" ]]; then
    return 0
  fi
  IFS= read -r password < "$file" || return 0
  password="${password%$'\r'}"
  if [[ -z "$password" ]]; then
    return 0
  fi
  export JASAGENT_CHECKPOINT=mysql
  export JASAGENT_CHECKPOINT_JDBC_URL="jdbc:mysql://127.0.0.1:3306/jasagent?connectTimeout=2000&socketTimeout=5000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED"
  export JASAGENT_CHECKPOINT_JDBC_USER="${JASAGENT_CHECKPOINT_JDBC_USER:-root}"
  export JASAGENT_CHECKPOINT_JDBC_PASSWORD="$password"
  echo "检查点使用本机 MySQL 127.0.0.1:3306/jasagent。" >&2
}

jasagent_load_model_env() {
  local file section line key value
  local gw_base="" gw_model="" gw_key="" ds_key="" in_gateway=0 first="" last=""
  file="${JASAGENT_LLGRAPH_ENV:-${HOME:-}/.config/llgraph/llgraph.env}"
  [[ -n "$file" && -f "$file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" || "${line:0:1}" == "#" ]] && continue
    if [[ "$line" == export[[:space:]]* ]]; then
      line="${line#export}"
      line="${line#"${line%%[![:space:]]*}"}"
    fi
    if [[ "$line" != *=* ]]; then
      section="$line"
      if jasagent_section_is_gateway "$section"; then
        in_gateway=1
      else
        in_gateway=0
      fi
      continue
    fi
    key="${line%%=*}"
    key="${key%"${key##*[![:space:]]}"}"
    value="${line#*=}"
    if [[ ${#value} -ge 2 ]]; then
      first="${value:0:1}"
      last="${value:$((${#value} - 1)):1}"
      if [[ "$first" == "$last" && ( "$first" == '"' || "$first" == "'" ) ]]; then
        value="${value:1:$((${#value} - 2))}"
      fi
    fi
    if [[ "$in_gateway" -eq 1 ]]; then
      case "$key" in
        LLGRAPH_API_BASE_URL) gw_base="$value" ;;
        LLGRAPH_MODEL) gw_model="$value" ;;
        LLGRAPH_API_KEY) gw_key="$value" ;;
      esac
    fi
    if [[ "$key" == "LLGRAPH_API_KEY" && -n "$value" ]]; then
      ds_key="$value"
    fi
  done < "$file"
  if [[ -z "${JASAGENT_DEEPSEEK_API_KEY:-}" && -n "$ds_key" ]]; then
    export JASAGENT_DEEPSEEK_API_KEY="$ds_key"
  fi
  if [[ -n "$gw_base" && -n "$gw_model" && -n "$gw_key" ]]; then
    if [[ -z "${JASAGENT_GATEWAY_BASE_URL:-}" ]]; then
      export JASAGENT_GATEWAY_BASE_URL="$gw_base"
    fi
    if [[ -z "${JASAGENT_GATEWAY_MODEL:-}" ]]; then
      export JASAGENT_GATEWAY_MODEL="$gw_model"
    fi
    if [[ -z "${JASAGENT_GATEWAY_API_KEY:-}" ]]; then
      export JASAGENT_GATEWAY_API_KEY="$gw_key"
    fi
  fi
  return 0
}

# 在指定目录里找一个 jar。没有时返回 1，不打印路径以外的内容。
jasagent_find_named_jar() {
  local dir="$1"
  local pattern="$2"
  [[ -d "$dir" ]] || return 1
  find "$dir" -maxdepth 1 -type f -name "$pattern" ! -name '*.jar.original' | sort | tail -n 1
}

# 打包网关、控制台上游和 agent。失败时把 mvn 的退出码交回去，不额外打印口令或令牌。
jasagent_build_stack() {
  if ! command -v mvn >/dev/null 2>&1; then
    echo "需要先打包，但找不到 mvn。" >&2
    exit 1
  fi
  rm -rf "$JASAGENT_ROOT/jasagent-gateway/src/main/resources/gateway-admin" \
    "$JASAGENT_ROOT/jasagent-gateway/target/classes/gateway-admin"
  (cd "$JASAGENT_ROOT" && mvn -pl jasagent-gateway,jasagent-web,jasagent-app -am -DskipTests package)
}

# 准备三个可执行 jar。缺网关、上游或 agent 时以非 0 退出。
jasagent_ensure_stack_jars() {
  local gateway="" web="" app=""
  gateway="$(jasagent_find_named_jar "$JASAGENT_ROOT/jasagent-gateway/target" 'jasagent-gateway-*.jar' || true)"
  web="$(jasagent_find_named_jar "$JASAGENT_ROOT/jasagent-web/target" 'jasagent-web-*-exec.jar' || true)"
  app="$(jasagent_find_named_jar "$JASAGENT_ROOT/jasagent-app/target" 'jasagent-app-*.jar' || true)"
  if [[ -z "$gateway" || -z "$web" || -z "$app" ]] \
    || jasagent_sources_newer_than "$gateway" \
    || jasagent_sources_newer_than "$web" \
    || jasagent_sources_newer_than "$app"; then
    jasagent_build_stack
    gateway="$(jasagent_find_named_jar "$JASAGENT_ROOT/jasagent-gateway/target" 'jasagent-gateway-*.jar' || true)"
    web="$(jasagent_find_named_jar "$JASAGENT_ROOT/jasagent-web/target" 'jasagent-web-*-exec.jar' || true)"
    app="$(jasagent_find_named_jar "$JASAGENT_ROOT/jasagent-app/target" 'jasagent-app-*.jar' || true)"
  fi
  if [[ -z "$gateway" || ! -f "$gateway" ]]; then
    echo "找不到网关 jar。" >&2
    exit 1
  fi
  if [[ -z "$web" || ! -f "$web" ]]; then
    echo "找不到控制台上游。" >&2
    exit 1
  fi
  if [[ -z "$app" || ! -f "$app" ]]; then
    echo "找不到 agent jar。" >&2
    exit 1
  fi
  JASAGENT_GATEWAY_JAR="$gateway"
  JASAGENT_WEB_JAR="$web"
  JASAGENT_APP_JAR="$app"
}

# 后台启动一个 jar。标准输出和错误只进日志。调用方不要把口令或令牌放进参数。
jasagent_spawn_jar() {
  local jar="$1"
  local log="$2"
  shift 2
  : > "$log"
  chmod 600 "$log"
  local pid
  pid="$(python3 - "$JASAGENT_JAVA" "$jar" "$log" "$@" <<'PY'
import os
import sys

java, jar, log, *args = sys.argv[1:]
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
    *args,
])
PY
)"
  printf '%s' "$pid" | tr -d '[:space:]'
}

# 记下后台进程号，并把 pid 文件收成仅属主可读。
jasagent_write_pid() {
  local file="$1"
  local pid="$2"
  printf '%s\n' "$pid" > "$file"
  chmod 600 "$file"
}

# TCP 端口已经接受连接时返回 0。不读取、也不打印响应正文。
jasagent_port_open() {
  local host="$1"
  local port="$2"
  python3 - "$host" "$port" <<'PY'
import socket
import sys

sock = socket.socket()
sock.settimeout(0.4)
try:
    sock.connect((sys.argv[1], int(sys.argv[2])))
except Exception:
    raise SystemExit(1)
finally:
    sock.close()
raise SystemExit(0)
PY
}

# GET 这个地址，状态是 200 且正文里有 status 和 up 时返回 0。不打印正文。
jasagent_http_status_up() {
  local url="$1"
  python3 - "$url" <<'PY'
import sys
import urllib.request

url = sys.argv[1]
try:
    with urllib.request.urlopen(url, timeout=1) as response:
        body = response.read(4096).decode("utf-8", "replace")
        if response.status == 200 and "status" in body and "up" in body:
            raise SystemExit(0)
except Exception:
    pass
raise SystemExit(1)
PY
}

# 等到进程还在，并且健康检查已经是 up。不读取启动完成那一行日志。
jasagent_wait_http_up() {
  local url="$1"
  local pid="$2"
  local n=0
  while [[ "$n" -lt 150 ]]; do
    n=$((n + 1))
    if ! kill -0 "$pid" 2>/dev/null; then
      return 1
    fi
    if jasagent_http_status_up "$url"; then
      return 0
    fi
    sleep 0.2
  done
  return 1
}

# 等到进程还在，并且端口已经接受连接。
jasagent_wait_port_open() {
  local host="$1"
  local port="$2"
  local pid="$3"
  local n=0
  while [[ "$n" -lt 150 ]]; do
    n=$((n + 1))
    if ! kill -0 "$pid" 2>/dev/null; then
      return 1
    fi
    if jasagent_port_open "$host" "$port"; then
      return 0
    fi
    sleep 0.2
  done
  return 1
}

jasagent_stop_pid() {
  local pid="$1"
  local _
  kill "$pid" 2>/dev/null || true
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25; do
    if ! kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
    sleep 0.2
  done
  kill -9 "$pid" 2>/dev/null || true
  for _ in 1 2 3 4 5; do
    if ! kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
    sleep 0.2
  done
  return 1
}

# 判断主机是不是回环或未指定地址。是则返回 0。不打印主机。
jasagent_is_loopback_host() {
  python3 - "$1" <<'PY'
import ipaddress
import socket
import sys

host = sys.argv[1].strip().strip("[]").lower()
if host in {"localhost", "localhost.localdomain", "::1", "0.0.0.0", "::"}:
    raise SystemExit(0)
try:
    addr = ipaddress.ip_address(host)
except ValueError:
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        raise SystemExit(0)
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if ip.is_loopback or ip.is_unspecified or ip.is_link_local:
            raise SystemExit(0)
    raise SystemExit(1)
if addr.is_loopback or addr.is_unspecified or addr.is_link_local:
    raise SystemExit(0)
raise SystemExit(1)
PY
}

# 选出网关能访问的非回环 IPv4。配置成回环地址时以非 0 退出，说明里不带口令。
jasagent_advertise_host() {
  if [[ -n "${JASAGENT_ADVERTISE_HOST:-}" ]]; then
    if jasagent_is_loopback_host "$JASAGENT_ADVERTISE_HOST"; then
      echo "注册地址不能是回环地址。" >&2
      exit 1
    fi
    printf '%s\n' "$JASAGENT_ADVERTISE_HOST"
    return 0
  fi
  local detected
  detected="$(python3 - <<'PY'
import ipaddress
import socket

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try:
    sock.connect(("10.255.255.255", 1))
    ip = sock.getsockname()[0]
finally:
    sock.close()
addr = ipaddress.ip_address(ip)
if addr.is_loopback or addr.is_unspecified or addr.is_link_local:
    raise SystemExit(1)
print(ip)
PY
)" || {
    echo "没有网关能访问的非回环地址。" >&2
    exit 1
  }
  if jasagent_is_loopback_host "$detected"; then
    echo "没有网关能访问的非回环地址。" >&2
    exit 1
  fi
  printf '%s\n' "$detected"
}

# 从环境或仓库外文件读取 Nacos 地址。缺失或为空时以非 0 退出，不打印地址和口令。
jasagent_load_nacos_env() {
  local file addr user_file user pass_file
  if [[ -z "${JASAGENT_NACOS_SERVER_ADDR:-}" ]]; then
    file="${JASAGENT_NACOS_SERVER_FILE:-${HOME}/.config/jasagent-nacos-server}"
    if [[ ! -f "$file" ]]; then
      echo "缺少 Nacos 地址。请设置 JASAGENT_NACOS_SERVER_ADDR，或提供仓库外的地址文件。" >&2
      exit 1
    fi
    IFS= read -r addr < "$file" || true
    addr="${addr%$'\r'}"
    addr="${addr#"${addr%%[![:space:]]*}"}"
    addr="${addr%"${addr##*[![:space:]]}"}"
    if [[ -z "$addr" || "$addr" != *:* || "$addr" == *" "* ]]; then
      echo "Nacos 地址无效。" >&2
      exit 1
    fi
    export JASAGENT_NACOS_SERVER_ADDR="$addr"
  fi
  if [[ -z "${JASAGENT_NACOS_USERNAME:-}" && -n "${JASAGENT_NACOS_USERNAME_FILE:-}" ]]; then
    user_file="$JASAGENT_NACOS_USERNAME_FILE"
    if [[ ! -f "$user_file" ]]; then
      echo "缺少 Nacos 用户名文件。" >&2
      exit 1
    fi
    IFS= read -r user < "$user_file" || true
    user="${user%$'\r'}"
    user="${user#"${user%%[![:space:]]*}"}"
    user="${user%"${user##*[![:space:]]}"}"
    if [[ -z "$user" ]]; then
      echo "Nacos 用户名文件是空的。" >&2
      exit 1
    fi
    export JASAGENT_NACOS_USERNAME="$user"
  fi
  if [[ -n "${JASAGENT_NACOS_USERNAME:-}" ]]; then
    pass_file="${JASAGENT_NACOS_PASSWORD_FILE:-${HOME}/.config/jasagent-nacos.password}"
    if [[ ! -f "$pass_file" ]]; then
      echo "缺少 Nacos 口令文件。" >&2
      exit 1
    fi
    export JASAGENT_NACOS_PASSWORD_FILE="$pass_file"
  elif [[ -n "${JASAGENT_NACOS_PASSWORD_FILE:-}" && ! -f "$JASAGENT_NACOS_PASSWORD_FILE" ]]; then
    echo "缺少 Nacos 口令文件。" >&2
    exit 1
  fi
}

# 地址已经能连上就直接用。连不上时用仓库外的安装拉起，输出只进日志，不打印口令。
jasagent_ensure_nacos() {
  local addr host port starter nacos_home n
  addr="${JASAGENT_NACOS_SERVER_ADDR:-}"
  port="${addr##*:}"
  host="${addr%:*}"
  if [[ -z "$host" || -z "$port" || "$host" == "$port" || ! "$port" =~ ^[0-9]+$ ]]; then
    echo "Nacos 地址无效。" >&2
    exit 1
  fi
  if jasagent_port_open "$host" "$port"; then
    return 0
  fi
  if [[ -n "${JASAGENT_NACOS_START:-}" ]]; then
    starter="$JASAGENT_NACOS_START"
  elif [[ -n "${JASAGENT_NACOS_HOME:-}" && -x "${JASAGENT_NACOS_HOME}/bin/startup.sh" ]]; then
    starter="${JASAGENT_NACOS_HOME}/bin/startup.sh"
  else
    echo "Nacos 未就绪，且没有可启动的安装。" >&2
    exit 1
  fi
  mkdir -p "$JASAGENT_RUNTIME_DIR"
  : > "$JASAGENT_NACOS_LOG_FILE"
  chmod 600 "$JASAGENT_NACOS_LOG_FILE"
  if [[ "$starter" == */bin/startup.sh ]]; then
    nacos_home="$(cd "$(dirname "$starter")/.." && pwd)"
    if ! (cd "$nacos_home" && MODE=standalone bash "$starter" -m standalone) >>"$JASAGENT_NACOS_LOG_FILE" 2>&1; then
      echo "Nacos 没有启动。" >&2
      exit 1
    fi
  elif ! bash "$starter" >>"$JASAGENT_NACOS_LOG_FILE" 2>&1; then
    echo "Nacos 没有启动。" >&2
    exit 1
  fi
  n=0
  while [[ "$n" -lt 150 ]]; do
    if jasagent_port_open "$host" "$port"; then
      printf '%s\n' "$starter" > "$JASAGENT_NACOS_MANAGED_FILE"
      chmod 600 "$JASAGENT_NACOS_MANAGED_FILE"
      return 0
    fi
    n=$((n + 1))
    sleep 0.2
  done
  echo "Nacos 没有在预期时间内就绪。" >&2
  exit 1
}

# 读取 Nacos 配置，必要时拉起，再导出非回环注册地址。不打印地址和口令。
jasagent_prepare_nacos() {
  local host
  jasagent_load_nacos_env
  jasagent_ensure_nacos
  host="$(jasagent_advertise_host)"
  export JASAGENT_ADVERTISE_HOST="$host"
}

# 停掉本入口拉起的 Nacos。没有管理标记时不动外部注册中心。
jasagent_stop_managed_nacos() {
  local starter shutdown
  [[ -f "$JASAGENT_NACOS_MANAGED_FILE" ]] || return 0
  starter="$(head -n 1 "$JASAGENT_NACOS_MANAGED_FILE" | tr -d '\r')"
  if [[ "$starter" == */bin/startup.sh ]]; then
    shutdown="$(cd "$(dirname "$starter")/.." && pwd)/bin/shutdown.sh"
    if [[ -x "$shutdown" ]]; then
      bash "$shutdown" >/dev/null 2>&1 || true
    fi
  elif [[ -n "${JASAGENT_NACOS_STOP:-}" ]]; then
    bash "$JASAGENT_NACOS_STOP" >/dev/null 2>&1 || true
  fi
  rm -f "$JASAGENT_NACOS_MANAGED_FILE"
}
