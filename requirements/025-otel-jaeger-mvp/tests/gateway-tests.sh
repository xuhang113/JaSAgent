#!/usr/bin/env bash
# 共用一次网关模块测试，给 T-003 到 T-005 复用。不调用官方合同脚本。
set -euo pipefail

# 本目录路径。
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/lib.sh"

# 这次要实际跑到的测试类。
ATDD_GATEWAY_CLASSES="JaegerPathsTest,JaegerProxyTest,GatewayHaContractTest,GatewayLayoutTest"
# 必须出现在 Surefire 报告里的方法。
ATDD_GATEWAY_METHODS=(
  anonymousJaegerRedirectsToLogin
  userCannotOpenJaeger
  adminIsProxiedWithoutSessionOrRewrittenHost
  prefixCannotFallThroughToTheConsole
  prefixCoversEncodingsAndStopsAtTheBoundary
  upstreamComesOnlyFromConfiguration
  proxyKeepsUpstreamCookiesAndPrefix
  loginSessionIsVisibleToAnotherReplica
)

# 复用已通过的摘要，否则跑网关模块测试并写入摘要。
atdd_run_gateway_tests() {
  local summary log
  summary="$(atdd_module_summary gateway)"
  if [[ -f "${summary}" ]] && grep -q 'PASS gateway-tests' "${summary}"; then
    local method
    for method in "${ATDD_GATEWAY_METHODS[@]}"; do
      if ! grep -q "PASS ${method}" "${summary}"; then
        echo "已有摘要缺少 ${method}，重新跑网关模块测试。" >&2
        rm -f "${summary}"
        break
      fi
    done
  fi
  if [[ -f "${summary}" ]] && grep -q 'PASS gateway-tests' "${summary}"; then
    return 0
  fi
  atdd_ensure_mysql
  mkdir -p "${ATDD_REQ}/logs"
  log="${ATDD_REQ}/logs/gateway-test.log"
  cd "${REPO_ROOT}"
  export JAVA_HOME="${ATDD_JAVA_HOME}"
  export PATH="${ATDD_JAVA_HOME}/bin:${ATDD_MAVEN_HOME}/bin:${PATH}"
  atdd_run env -u JASAGENT_JDBC_URL -u JASAGENT_JDBC_PASSWORD -u JASAGENT_JDBC_USER \
    mvn -pl jasagent-gateway -am test \
    -Dexec.skip=true \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest="${ATDD_GATEWAY_CLASSES}"
  printf '%s' "${ATDD_CAPTURE_TEXT}" | atdd_redact > "${log}"
  printf '%s' "${ATDD_CAPTURE_TEXT}" | atdd_assert_clean "网关模块测试输出"
  if [[ "${ATDD_CAPTURE_CODE}" -ne 0 ]]; then
    echo "网关模块测试没有通过。" >&2
    tail -n 80 "${log}" >&2
    exit 1
  fi
  if ! grep -q 'BUILD SUCCESS' "${log}"; then
    echo "网关模块测试没有看到 BUILD SUCCESS。" >&2
    exit 1
  fi
  atdd_write_surefire_summary "${summary}" "jasagent-gateway" "${ATDD_GATEWAY_METHODS[@]}"
  printf 'PASS gateway-tests\n' >> "${summary}"
}

atdd_run_gateway_tests
