#!/usr/bin/env bash
# 023 ATDD 共用夹具。只给本目录用例使用，不改产品代码。
set -euo pipefail

# 本目录路径。
ATDD_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 023 需求目录。
ATDD_REQ="$(cd "${ATDD_HERE}/.." && pwd)"
# 仓库根目录。
REPO_ROOT="$(cd "${ATDD_HERE}/../../.." && pwd)"

# 已确认的规格提交。实现和测试都应从这个点往后比。
ATDD_SPEC_BASE="fae59178d32eeffacf2d3f84db6737f724b26220"

# 假的口令，只用于确认输出里没有密文。
ATDD_SECRET="agentpass-secret-value-023-atdd"
# 假的票据，只用于确认输出里没有密文。
ATDD_TICKET="ticket-secret-value-023-atdd"

# 本机编译和模块测试用的 JDK。
ATDD_JAVA_HOME="/home/box/opt/jdk-21"
# 本机编译和模块测试用的 Maven。
ATDD_MAVEN_HOME="/home/box/opt/maven"

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

# 把密文打成占位符后再输出。
atdd_redact() {
  local text="$1"
  text="${text//${ATDD_SECRET}/[redacted-secret]}"
  text="${text//${ATDD_TICKET}/[redacted-ticket]}"
  printf '%s' "${text}"
}

# 文本里不能出现测试用的口令或票据。
atdd_assert_clean() {
  local text="$1"
  local label="$2"
  if [[ "${text}" == *"${ATDD_SECRET}"* || "${text}" == *"${ATDD_TICKET}"* ]]; then
    echo "${label} 出现了密文。" >&2
    exit 1
  fi
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
  printf '%s\t%s\t%s\t%s\t%s\n' "$(atdd_now_time)" "$name" "$check" "$elapsed" "$(atdd_redact "${detail}")"
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
# 第一个参数是要实际跑到用例的模块列表，第二个参数是 Surefire 的 -Dtest 列表。
atdd_mvn_test() {
  local modules="$1"
  local classes="$2"
  atdd_run env -u JASAGENT_JDBC_URL -u JASAGENT_JDBC_PASSWORD -u JASAGENT_JDBC_USER -u JASAGENT_AGENTPASS_SIGNING_KEY \
    JASAGENT_CHECKPOINT=sqlite \
    JASAGENT_CHECKPOINT_JDBC_URL="jdbc:sqlite:${ATDD_REQ}/logs/checkpoint-${ATDD_NAME}.db" \
    mvn -pl "${modules}" -am test \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest="${classes}"
  atdd_assert_clean "${ATDD_CAPTURE_TEXT}" "模块测试输出"
  atdd_checkpoint "${ATDD_NAME}" "${ATDD_START_MS}" "模块测试 ${classes}" "退出码 ${ATDD_CAPTURE_CODE}" >> "${ATDD_LOG}"
  if [[ "${ATDD_CAPTURE_CODE}" -ne 0 ]]; then
    echo "模块测试没有通过：${classes}" >&2
    atdd_redact "${ATDD_CAPTURE_TEXT}" >&2
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

# 进程级验收摘要。live.sh 成功后写入，供多条用例复用同一次拉起。
atdd_live_summary() {
  printf '%s\n' "${ATDD_REQ}/logs/live-summary.txt"
}

# 确认进程级摘要里有指定的通过标记。
atdd_require_live_pass() {
  local marker="$1"
  local summary
  summary="$(atdd_live_summary)"
  if [[ ! -f "${summary}" ]] || ! grep -q "PASS ${marker}" "${summary}"; then
    echo "进程级验收没有通过：${marker}" >&2
    if [[ -f "${summary}" ]]; then
      grep -E 'PASS |FAIL ' "${summary}" >&2 || true
    fi
    exit 1
  fi
}
