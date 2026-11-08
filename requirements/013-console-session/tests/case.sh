#!/usr/bin/env bash
# 单条验收共用的执行方式。结果文件只在真正跑完 Maven 之后写入。
run_case() {
  local id="$1"
  local module="$2"
  local class_name="$3"
  local method="$4"
  local expect="$5"
  local here
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  local root
  root="$(cd "$here/../../.." && pwd)"
  if [[ "${JAVA_TOOL_OPTIONS:-}" != *net.bytebuddy.experimental* ]]; then
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dnet.bytebuddy.experimental=true"
  fi
  if ! javac --release 21 -version >/dev/null 2>&1; then
    local candidate
    for candidate in \
      /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
      /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
      /usr/libexec/java_home
    do
      if [[ "$candidate" == "/usr/libexec/java_home" ]]; then
        candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
      fi
      if [[ -n "$candidate" && -x "$candidate/bin/javac" ]]; then
        export JAVA_HOME="$candidate"
        export PATH="$JAVA_HOME/bin:$PATH"
        break
      fi
    done
  fi
  local results="$here/../results"
  local logs="$here/../logs"
  mkdir -p "$results" "$logs"
  local start end dur now status code mvnlog
  start="$(python3 -c 'import time; print(int(time.time()*1000))')"
  mvnlog="$logs/$id.maven"
  set +e
  (cd "$root" && mvn -pl "$module" -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest="${class_name}#${method}") >"$mvnlog" 2>&1
  code=$?
  set -e
  if ! grep -q "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0" "$mvnlog"; then
    code=1
  fi
  end="$(python3 -c 'import time; print(int(time.time()*1000))')"
  dur="$((end - start))"
  now="$(python3 -c 'import datetime; print(datetime.datetime.now().astimezone().isoformat(timespec="seconds"))')"
  if [[ "$code" -eq 0 ]]; then
    status="通过"
  else
    status="失败"
  fi
  printf '%s\t%s\t%s\t%s\t%s\n' "$now" "$id" "执行" "$dur" "$expect" >"$logs/$id.log"
  printf '%s\t%s\t%s\t%s\t%s\n' "$id" "$status" "$expect" "$dur" "logs/$id.log" >"$results/$id.txt"
  return "$code"
}
