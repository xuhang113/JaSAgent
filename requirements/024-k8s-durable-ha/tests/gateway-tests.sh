#!/usr/bin/env bash
# 共用一次网关模块测试，给 T-001 到 T-004 复用。不调用官方合同脚本。
set -euo pipefail

# 本目录路径。
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/lib.sh"

# 这次要实际跑到的测试类。
ATDD_GATEWAY_CLASSES="SharedAgentLivenessTest,GatewayHaContractTest,CEndTenantImTest"
# 必须出现在 Surefire 报告里的方法。
ATDD_GATEWAY_METHODS=(
  twoReplicasSeeTheSameLiveness
  oneReplicaDownDoesNotEraseTheOtherHeartbeat
  expiredHeartbeatDoesNotKeepTheAgentAlive
  routingUsesSharedLivenessInsteadOfTheLocalTable
  clusterSnapshotReadsSharedLiveness
  loginSessionIsVisibleToAnotherReplica
  jdbcUrlRejectsEmbeddedPassword
  failureTextDoesNotContainThePassword
  eventStreamIsForwardedWithoutReadingTheBody
  forwardedTrustRejectsTheWholeInternet
  nonAdminCannotReachClusterApiOrPage
  productionWiringUsesSharedLiveness
  anonymousCallsDoNotLeakBusinessData
  registerCreatesAnOrdinaryTenantAndCanEnterTheConsole
  gatewayRejectsForeignSessionsBeforeForwarding
  clusterIsAdminOnlyAndImportedOperatorKeepsAdmin
)

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
    echo "本机找不到 mysqld，024 不改用内存库或新建库代替。" >&2
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
  echo "本机 MySQL 没有起来，024 不改用内存库或新建库代替。" >&2
  exit 1
}

# 从 Surefire 报告写出通过标记。缺方法或失败则退出。
atdd_write_gateway_summary() {
  python3 - "${REPO_ROOT}" "$(atdd_gateway_summary)" <<'CHK'
import pathlib
import sys
import xml.etree.ElementTree as ET

repo = pathlib.Path(sys.argv[1])
summary = pathlib.Path(sys.argv[2])
wanted = [
  "twoReplicasSeeTheSameLiveness",
  "oneReplicaDownDoesNotEraseTheOtherHeartbeat",
  "expiredHeartbeatDoesNotKeepTheAgentAlive",
  "routingUsesSharedLivenessInsteadOfTheLocalTable",
  "clusterSnapshotReadsSharedLiveness",
  "loginSessionIsVisibleToAnotherReplica",
  "jdbcUrlRejectsEmbeddedPassword",
  "failureTextDoesNotContainThePassword",
  "eventStreamIsForwardedWithoutReadingTheBody",
  "forwardedTrustRejectsTheWholeInternet",
  "nonAdminCannotReachClusterApiOrPage",
  "productionWiringUsesSharedLiveness",
  "anonymousCallsDoNotLeakBusinessData",
  "registerCreatesAnOrdinaryTenantAndCanEnterTheConsole",
  "gatewayRejectsForeignSessionsBeforeForwarding",
  "clusterIsAdminOnlyAndImportedOperatorKeepsAdmin",
]
reports = list((repo / "jasagent-gateway/target/surefire-reports").glob("TEST-*.xml"))
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
    print("网关模块测试失败：" + ", ".join(failed), file=sys.stderr)
    raise SystemExit(1)
missing = [name for name in wanted if name not in seen]
if missing:
    print("网关模块测试没有跑到：" + ", ".join(missing), file=sys.stderr)
    raise SystemExit(1)
lines = ["PASS " + name for name in wanted]
lines.append("PASS gateway-tests")
summary.write_text("\n".join(lines) + "\n", encoding="utf-8")
print("gateway-tests-ok")
CHK
}

# 复用已通过的摘要，否则跑网关模块测试并写入摘要。
atdd_run_gateway_tests() {
  local summary log
  summary="$(atdd_gateway_summary)"
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
  atdd_write_gateway_summary
}

atdd_run_gateway_tests
