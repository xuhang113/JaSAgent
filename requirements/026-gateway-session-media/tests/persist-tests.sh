#!/usr/bin/env bash
# 共用一次持久化模块测试，给 T-002 和 T-008 复用。不调用官方合同脚本。
set -euo pipefail

# 本目录路径。
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/lib.sh"

# 这次要实际跑到的测试类。
ATDD_PERSIST_CLASSES="SessionAttachmentLedgerTest,SessionLedgerMysqlTest"
# 必须出现在 Surefire 报告里的方法。
ATDD_PERSIST_METHODS=(
  displayNameDropsDirectories
  bytesStayInJasagentAndFollowTheSessionTenant
  ownershipStaysInMysqlAndFollowsTheUserTenant
)

# 复用已通过的摘要，否则跑持久化模块测试并写入摘要。
atdd_run_persist_tests() {
  local summary log
  summary="$(atdd_module_summary persist)"
  if [[ -f "${summary}" ]] && grep -q 'PASS persist-tests' "${summary}"; then
    local method
    for method in "${ATDD_PERSIST_METHODS[@]}"; do
      if ! grep -q "PASS ${method}" "${summary}"; then
        echo "已有摘要缺少 ${method}，重新跑持久化模块测试。" >&2
        rm -f "${summary}"
        break
      fi
    done
  fi
  if [[ -f "${summary}" ]] && grep -q 'PASS persist-tests' "${summary}"; then
    return 0
  fi
  atdd_ensure_mysql
  mkdir -p "${ATDD_REQ}/logs"
  log="${ATDD_REQ}/logs/persist-test.log"
  cd "${REPO_ROOT}"
  export JAVA_HOME="${ATDD_JAVA_HOME}"
  export PATH="${ATDD_JAVA_HOME}/bin:${ATDD_MAVEN_HOME}/bin:${PATH}"
  atdd_mvn_test jasagent-persist "${ATDD_PERSIST_CLASSES}"
  printf '%s' "${ATDD_CAPTURE_TEXT}" | atdd_redact > "${log}"
  atdd_write_surefire_summary "${summary}" "jasagent-persist" "${ATDD_PERSIST_METHODS[@]}"
  printf 'PASS persist-tests\n' >> "${summary}"
}

atdd_run_persist_tests
