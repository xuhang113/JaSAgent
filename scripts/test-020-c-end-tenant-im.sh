#!/usr/bin/env bash
# 020 的仓库内验收，覆盖规格第 1 到第 13 条。
# 不修改 deploy/k8s，不部署，不改公网入口。
# 第 14 到第 16 条是部署阶段，本脚本不覆盖，也不把它们算作通过。
set -euo pipefail

# 仓库根目录。
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo}"

# 打印一条验收项。
section() {
  printf 'TEST %s\n' "$1"
}

# 打印一条通过。
pass() {
  printf 'PASS %s\n' "$1"
}

# 函数或常量的上一行必须是注释。
assert_comments() {
  local file="$1"
  local prev="" line
  while IFS= read -r line || [[ -n "${line}" ]]; do
    if [[ "${line}" =~ ^[a-zA-Z_][a-zA-Z0-9_]*\(\)[[:space:]]*\{$ || "${line}" =~ ^[A-Z][A-Z0-9_]+= ]]; then
      if [[ "${prev}" != \#* ]]; then
        echo "${file} 缺少注释：${line}" >&2
        exit 1
      fi
    fi
    if [[ -n "${line//[[:space:]]/}" ]]; then
      prev="${line}"
    fi
  done < "${file}"
}

section "12 改动范围"
assert_comments "${repo}/scripts/test-020-c-end-tenant-im.sh"
changed="$(
  git diff --name-only master
  git ls-files --others --exclude-standard
)"
while IFS= read -r path; do
  [[ -z "${path}" ]] && continue
  case "${path}" in
    jasagent-gateway/*|jasagent-web/*|jasagent-auth/*|jasagent-persist/*) ;;
    changelog/2026-11-18-c-end-tenant-im.md) ;;
    scripts/test-020-c-end-tenant-im.sh) ;;
    *)
      echo "改动超出 020 允许的模块：${path}" >&2
      exit 1
      ;;
  esac
  case "${path}" in
    deploy/k8s/*|jasagent-sidecar/*|*/tdd.md|results/*|*/deploy.md)
      echo "改动碰到了禁止目录：${path}" >&2
      exit 1
      ;;
  esac
done <<< "${changed}"
pass "只改了允许的模块、变更记录和本脚本"

section "11 与 12 上游、单副本和跳转"
props="${repo}/jasagent-gateway/src/main/resources/application.properties"
if ! grep -q '^jasagent.gateway.web-origin=http://127.0.0.1:18722$' "${props}"; then
  echo "本机 web 上游被改掉了。" >&2
  exit 1
fi
if ! grep -q '^jasagent.gateway.replicas=1$' "${props}"; then
  echo "网关副本配置不是 1。" >&2
  exit 1
fi
if grep -R -n -E 'https?://[^[:space:]]*ts\.net|[A-Za-z0-9][A-Za-z0-9.-]*\.ts\.net' \
  "${repo}/jasagent-web/frontend" "${props}" >/dev/null; then
  echo "前端或网关配置写进了公网主机名。" >&2
  exit 1
fi
if grep -R -n -E 'jdbc:[^[:space:]"]*password=' \
  "${repo}/jasagent-auth/src" \
  "${repo}/jasagent-gateway/src/main" \
  "${repo}/jasagent-persist/src/main" \
  "${repo}/jasagent-web" >/dev/null; then
  echo "JDBC 地址里带了 password=。" >&2
  exit 1
fi
if grep -q 'sendRedirect(' "${repo}/jasagent-gateway/src/main/java/dev/jasagent/gateway/auth/GatewaySessionFilter.java"; then
  echo "未登录跳转仍会拼绝对地址。" >&2
  exit 1
fi
pass "上游仍是本机 web，网关仍是单副本，跳转保持相对路径"

section "2 与 12 没有加入已有租户，也没有密钥"
if grep -R -n -E '@(Post|Get|Request)Mapping\([^)]*(/join|tenants)' \
  "${repo}/jasagent-gateway/src/main" \
  "${repo}/jasagent-web/src" >/dev/null; then
  echo "出现了加入已有租户的接口。" >&2
  exit 1
fi
if grep -R -n -E 'tskey-|BEGIN PRIVATE KEY|AKIA[0-9A-Z]{16}' \
  "${repo}/jasagent-auth/src/main" \
  "${repo}/jasagent-gateway/src/main" \
  "${repo}/jasagent-persist/src/main" \
  "${repo}/jasagent-web/src" \
  "${repo}/changelog/2026-11-18-c-end-tenant-im.md" >/dev/null; then
  echo "仓库里出现了密钥材料。" >&2
  exit 1
fi
pass "没有加入租户的接口，也没有写入口令或令牌"

section "1-13 模块测试"
if ! command -v mvn >/dev/null 2>&1; then
  echo "找不到 mvn。" >&2
  exit 1
fi
mvn -pl jasagent-auth,jasagent-persist,jasagent-gateway,jasagent-web -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=MysqlAccountAuthTest,SessionLedgerMysqlTest,ChatSessionGateTest,CEndTenantImTest,GatewayForwardTest,GatewayContractTest,ConsoleProxyTest,PublicIngressFilterTest,GatewayLayoutTest,NacosContractTest,WebUpstreamTest
classes=(
  "jasagent-auth|dev.jasagent.auth.MysqlAccountAuthTest"
  "jasagent-persist|dev.jasagent.persist.SessionLedgerMysqlTest"
  "jasagent-gateway|dev.jasagent.gateway.proxy.ChatSessionGateTest"
  "jasagent-gateway|dev.jasagent.gateway.CEndTenantImTest"
  "jasagent-gateway|dev.jasagent.gateway.GatewayForwardTest"
  "jasagent-gateway|dev.jasagent.gateway.route.GatewayContractTest"
  "jasagent-gateway|dev.jasagent.gateway.ConsoleProxyTest"
  "jasagent-gateway|dev.jasagent.gateway.ingress.PublicIngressFilterTest"
  "jasagent-web|dev.jasagent.web.upstream.WebUpstreamTest"
)
for item in "${classes[@]}"; do
  module="${item%%|*}"
  class="${item#*|}"
  report="${repo}/${module}/target/surefire-reports/${class}.txt"
  if [[ ! -f "${report}" ]]; then
    echo "没有跑到 ${class}。" >&2
    exit 1
  fi
  summary="$(grep -E 'Tests run:' "${report}" | tail -n 1)"
  if [[ "${summary}" != *"Failures: 0"* || "${summary}" != *"Errors: 0"* ]]; then
    echo "测试没有通过：${class}" >&2
    printf '%s\n' "${summary}" >&2
    exit 1
  fi
done
if ! command -v npm >/dev/null 2>&1; then
  echo "找不到 npm。" >&2
  exit 1
fi
(cd "${repo}/jasagent-web/frontend" && npm test)
pass "账号、隔离、会话壳和 019 Cookie 的仓库内测试"

section "14-16 留给部署"
printf '第 14 到第 16 条要求在部署的云电脑上重启网关、走公网 Funnel 并跑官方探测。本脚本不改 deploy/k8s，也不把这三条算作通过。\n'
pass "部署项保持未执行"

printf '020 TEST PASSED\n'
