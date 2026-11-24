#!/usr/bin/env bash
# 025 ATDD 共用夹具。只给本目录用例使用，不改产品代码。
set -euo pipefail

# 本目录路径。
ATDD_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 025 需求目录。
ATDD_REQ="$(cd "${ATDD_HERE}/.." && pwd)"
# 仓库根目录。
REPO_ROOT="$(cd "${ATDD_HERE}/../../.." && pwd)"

# 已确认的规格提交。实现和测试都应从这个点往后比。
ATDD_SPEC_BASE="1ac39ca07ee7b152453012626d9d094c4a8be9e2"

# 本机编译和模块测试用的 JDK。
ATDD_JAVA_HOME="/home/box/opt/jdk-21"
# 本机编译和模块测试用的 Maven。
ATDD_MAVEN_HOME="/home/box/opt/maven"

# 仓库外的数据库口令文件。只检查它在不在，不把原文写进仓库或日志。
ATDD_PASSWORD_FILE="${HOME}/.config/urag-mysql-root.password"
# MySQL 套接字。
ATDD_MYSQL_SOCK="/run/mysqld/mysqld.sock"
# mysqld 进程的 pid 文件。
ATDD_MYSQL_PID="/run/mysqld/mysqld.pid"
# mysqld 启动日志，不提交到 Git。
ATDD_MYSQL_LOG="/tmp/mariadbd-025-atdd.log"

# 用例开始时的毫秒时间戳。
ATDD_START_MS=0
# 当前用例编号。
ATDD_NAME=""
# 当前用例的“则”文本。
ATDD_RESULT_TEXT=""
# 当前用例日志路径。
ATDD_LOG=""
# 最近一次捕获的命令输出。
ATDD_CAPTURE_TEXT=""
# 最近一次捕获的退出码。
ATDD_CAPTURE_CODE=0

# 当前毫秒时间戳。
atdd_now_ms() {
  python3 -c 'import time; print(int(time.time()*1000))'
}

# 当前本地时间，用于日志。
atdd_now_time() {
  date '+%Y-%m-%dT%H:%M:%S%z'
}

# 从标准输入读文本，把仓库外口令打成占位符后再输出，避免写进结果或日志。
atdd_redact() {
  python3 -c 'import pathlib,sys
text=sys.stdin.read()
path=pathlib.Path.home()/".config/urag-mysql-root.password"
if path.is_file():
    secret=path.read_text(encoding="utf-8").strip()
    if secret:
        text=text.replace(secret,"[redacted-password]")
sys.stdout.write(text)
'
}

# 从标准输入读文本，确认里面没有仓库外口令文件里的原文。参数是说明标签。
atdd_assert_clean() {
  local label="$1"
  python3 -c 'import pathlib,sys
label=sys.argv[1]
text=sys.stdin.read()
path=pathlib.Path.home()/".config/urag-mysql-root.password"
if path.is_file():
    secret=path.read_text(encoding="utf-8").strip()
    if secret and secret in text:
        raise SystemExit(label+" 出现了密文。")
' "${label}"
}

# 写一行检查点到用例日志。
atdd_checkpoint() {
  local name="$1"
  local started="$2"
  local check="$3"
  local detail="$4"
  local now elapsed
  now="$(atdd_now_ms)"
  elapsed=$((now - started))
  printf '%s\t%s\t%s\t%s\t%s\n' "$(atdd_now_time)" "$name" "$check" "$elapsed" "$(printf '%s' "${detail}" | atdd_redact)"
}

# 准备结果和日志目录，记录开始时间。
atdd_begin() {
  ATDD_NAME="$1"
  ATDD_RESULT_TEXT="$2"
  mkdir -p "${ATDD_REQ}/results" "${ATDD_REQ}/logs"
  ATDD_LOG="${ATDD_REQ}/logs/${ATDD_NAME}.log"
  : > "${ATDD_LOG}"
  ATDD_START_MS="$(atdd_now_ms)"
  cd "${REPO_ROOT}"
  export JAVA_HOME="${ATDD_JAVA_HOME}"
  export PATH="${ATDD_JAVA_HOME}/bin:${ATDD_MAVEN_HOME}/bin:${PATH}"
}

# 写出结果文件。必须在用例逻辑结束后调用，不能提前 exit。
atdd_finish() {
  local code="$1"
  local end elapsed status
  end="$(atdd_now_ms)"
  elapsed=$((end - ATDD_START_MS))
  if [[ "${code}" -eq 0 ]]; then
    status="通过"
  else
    status="失败"
  fi
  printf '%s\t%s\t%s\t%s\tlogs/%s.log\n' "${ATDD_NAME}" "${status}" "${ATDD_RESULT_TEXT}" "${elapsed}" "${ATDD_NAME}" > "${ATDD_REQ}/results/${ATDD_NAME}.txt"
  return "${code}"
}

# 运行一个函数或命令，把输出和退出码留在 ATDD_CAPTURE_*。
atdd_run() {
  set +e
  ATDD_CAPTURE_TEXT="$(
    set -euo pipefail
    "$@" 2>&1
  )"
  ATDD_CAPTURE_CODE=$?
  set -e
}

# 跑实现模块里与本条验收对应的测试。失败时把打码后的输出写到标准错误。
# 第一个参数是要实际跑到用例的模块列表，第二个参数是 Surefire 的 -Dtest 列表，其余参数原样传给 Maven。
atdd_mvn_test() {
  local modules="$1"
  local classes="$2"
  shift 2
  atdd_run env -u JASAGENT_JDBC_URL -u JASAGENT_JDBC_PASSWORD -u JASAGENT_JDBC_USER \
    JASAGENT_CHECKPOINT=sqlite \
    JASAGENT_CHECKPOINT_JDBC_URL="jdbc:sqlite:${ATDD_REQ}/logs/checkpoint-${ATDD_NAME}.db" \
    mvn -pl "${modules}" -am test \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest="${classes}" \
    "$@"
  printf '%s' "${ATDD_CAPTURE_TEXT}" | atdd_assert_clean "模块测试输出"
  atdd_checkpoint "${ATDD_NAME}" "${ATDD_START_MS}" "模块测试 ${classes}" "退出码 ${ATDD_CAPTURE_CODE}" >> "${ATDD_LOG}"
  if [[ "${ATDD_CAPTURE_CODE}" -ne 0 ]]; then
    echo "模块测试没有通过：${classes}" >&2
    printf '%s' "${ATDD_CAPTURE_TEXT}" | atdd_redact >&2
    echo >&2
    exit 1
  fi
  if ! grep -q 'BUILD SUCCESS' <<<"${ATDD_CAPTURE_TEXT}"; then
    echo "模块测试没有看到 BUILD SUCCESS：${classes}" >&2
    exit 1
  fi
  local module
  IFS=',' read -r -a expected <<< "${modules}"
  for module in "${expected[@]}"; do
    module="${module// /}"
    if ! awk -v want="${module}" '
      BEGIN { hit=0; inmod=0 }
      index($0, "Building " want " ") { inmod=1; next }
      /Building / { inmod=0 }
      inmod && /Tests run: [1-9]/ { hit=1 }
      END { exit hit ? 0 : 1 }
    ' <<<"${ATDD_CAPTURE_TEXT}"; then
      echo "${module} 没有实际跑到用例：${classes}" >&2
      exit 1
    fi
  done
}

# 函数定义的上一行必须是注释。第二个参数为 1 时，顶层常量也要有注释。
atdd_assert_comments() {
  local file="$1"
  local check_constants="${2:-0}"
  local prev="" line n=0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    n=$((n + 1))
    if [[ "${line}" =~ ^[a-zA-Z_][a-zA-Z0-9_]*\(\)[[:space:]]*\{$ || ( "${check_constants}" == "1" && "${line}" =~ ^[A-Z][A-Z0-9_]+= ) ]]; then
      if [[ "${prev}" != \#* ]]; then
        echo "${file}:${n} 缺少注释：${line}" >&2
        exit 1
      fi
    fi
    if [[ -n "${line//[[:space:]]/}" ]]; then
      prev="${line}"
    fi
  done < "${file}"
}

# 模块测试摘要路径。参数是 app、gateway 或 web。
atdd_module_summary() {
  printf '%s\n' "${ATDD_REQ}/logs/${1}-test-summary.txt"
}

# 确认指定摘要里有通过标记。第一个参数是种类，第二个参数是标记。
atdd_require_pass() {
  local kind="$1"
  local marker="$2"
  local summary
  summary="$(atdd_module_summary "${kind}")"
  if [[ ! -f "${summary}" ]] || ! grep -q "PASS ${marker}" "${summary}"; then
    echo "${kind} 模块测试没有通过：${marker}" >&2
    if [[ -f "${summary}" ]]; then
      grep -E 'PASS |FAIL ' "${summary}" >&2 || true
    fi
    exit 1
  fi
}

# 确认本机 MySQL 在听，并且仓库外口令文件存在。起不来就失败，不改用内存库。
atdd_ensure_mysql() {
  if [[ ! -f "${ATDD_PASSWORD_FILE}" ]]; then
    echo "缺少仓库外的数据库口令文件，测试不在仓库里写口令。" >&2
    exit 1
  fi
  if sudo mysqladmin --socket="${ATDD_MYSQL_SOCK}" ping >/dev/null 2>&1; then
    return 0
  fi
  local mysqld
  mysqld="$(command -v mysqld || command -v mariadbd || true)"
  if [[ -z "${mysqld}" ]]; then
    echo "本机找不到 mysqld，025 不改用内存库或新建库代替。" >&2
    exit 1
  fi
  sudo mkdir -p /run/mysqld
  sudo chown mysql:mysql /run/mysqld
  sudo "${mysqld}" --user=mysql --datadir=/var/lib/mysql --bind-address=127.0.0.1 --port=3306 --socket="${ATDD_MYSQL_SOCK}" --pid-file="${ATDD_MYSQL_PID}" >"${ATDD_MYSQL_LOG}" 2>&1 &
  local i
  for i in $(seq 1 40); do
    if sudo mysqladmin --socket="${ATDD_MYSQL_SOCK}" ping >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "本机 MySQL 没有起来，025 不改用内存库或新建库代替。" >&2
  exit 1
}

# 根据指定模块的 Surefire 报告写出通过标记。第一个参数是摘要路径，第二个是模块目录列表，其余是必须出现的方法名。
atdd_write_surefire_summary() {
  local summary="$1"
  local modules="$2"
  shift 2
  python3 - "${REPO_ROOT}" "${summary}" "${modules}" "$@" <<'CHK'
import pathlib
import sys
import xml.etree.ElementTree as ET

repo = pathlib.Path(sys.argv[1])
summary = pathlib.Path(sys.argv[2])
modules = [item.strip() for item in sys.argv[3].split(",") if item.strip()]
wanted = list(sys.argv[4:])
reports = []
for module in modules:
    reports.extend((repo / module / "target/surefire-reports").glob("TEST-*.xml"))
if not reports:
    raise SystemExit("没有找到 Surefire 报告。")
seen = {}
failed = []
for path in reports:
    root = ET.parse(path).getroot()
    for case in root.iter("testcase"):
        name = case.get("name") or ""
        if case.find("failure") is not None or case.find("error") is not None:
            failed.append(name)
        seen[name] = True
if failed:
    print("模块测试失败：" + ", ".join(failed), file=sys.stderr)
    raise SystemExit(1)
missing = [name for name in wanted if name not in seen]
if missing:
    print("模块测试没有跑到：" + ", ".join(missing), file=sys.stderr)
    raise SystemExit(1)
lines = ["PASS " + name for name in wanted]
summary.write_text("\n".join(lines) + "\n", encoding="utf-8")
print("surefire-summary-ok")
CHK
}
