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

jasagent_read_pid() {
  local pid
  [[ -f "$JASAGENT_PID_FILE" ]] || return 1
  pid="$(tr -d '[:space:]' < "$JASAGENT_PID_FILE")"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$pid"
}

jasagent_is_server_command() {
  local cmd="$1"
  [[ "$cmd" == *jasagent-app-*.jar* || "$cmd" == *dev.jasagent.app.JasagentApplication* ]]
}

jasagent_running_pid() {
  local pid cmd
  pid="$(jasagent_read_pid)" || return 1
  if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$JASAGENT_PID_FILE"
    return 1
  fi
  cmd="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  if jasagent_is_server_command "$cmd"; then
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
  done < <(find "$JASAGENT_ROOT/pom.xml" "$JASAGENT_ROOT"/jasagent-*/pom.xml "$JASAGENT_ROOT"/jasagent-*/src/main -type f -newer "$jar" 2>/dev/null || true)
  return 1
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
