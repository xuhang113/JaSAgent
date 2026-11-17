#!/usr/bin/env bash
# 019 的合同测试，覆盖规格验收第 1 到第 8 条。
# 不修改 deploy/k8s，不修改 jasagent-web，不部署 Funnel 或 Ingress。
# 第 9 到第 14 条是部署阶段，本脚本不覆盖，也不把它们算作通过。
set -euo pipefail

# 仓库根目录。
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo}"

# 019 规格提交。实现相对它只能改网关、变更记录和本脚本。
spec_base="c4ab8c68f4dd83b791b8b908aa22f0b590b81055"

# 避免外部环境把上游或信任名单改掉，测试要看到仓库里的默认值。
unset JASAGENT_GATEWAY_FORWARDED_TRUSTED || true
unset JASAGENT_GATEWAY_WEB_ORIGIN || true

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

section "1-8 改动范围"
assert_comments "${repo}/scripts/test-019-gateway-public-ingress.sh"
changed="$(
  git diff --name-only "${spec_base}"
  git ls-files --others --exclude-standard
)"
while IFS= read -r path; do
  [[ -z "${path}" ]] && continue
  case "${path}" in
    jasagent-gateway/*) ;;
    changelog/2026-11-17-gateway-public-ingress.md) ;;
    scripts/test-019-gateway-public-ingress.sh) ;;
    *)
      echo "改动超出 019 的范围：${path}" >&2
      exit 1
      ;;
  esac
done <<< "${changed}"
pass "只改了 jasagent-gateway、变更记录和本脚本"

section "1 与 7 上游和单副本"
props="${repo}/jasagent-gateway/src/main/resources/application.properties"
gateway="${repo}/deploy/k8s/gateway.yaml"
if ! grep -q '^jasagent.gateway.web-origin=http://127.0.0.1:18722$' "${props}"; then
  echo "本机 web 上游被改掉了。" >&2
  exit 1
fi
if ! grep -q 'value: http://jasagent-web.jasagent.svc.cluster.local:18722' "${gateway}"; then
  echo "集群内 web 上游被改掉了。" >&2
  exit 1
fi
if grep -E '^[^#].*(\.ts\.net|trycloudflare)' "${props}" "${gateway}"; then
  echo "上游或清单里出现了公网主机名。" >&2
  exit 1
fi
if ! grep -q '^jasagent.gateway.replicas=1$' "${props}"; then
  echo "网关副本配置不是 1。" >&2
  exit 1
fi
if ! grep -q 'replicas: 1' "${gateway}" || grep -q 'replicas: [2-9]' "${gateway}"; then
  echo "集群网关不再是单副本。" >&2
  exit 1
fi
pass "上游仍指向本机和集群内 web，网关仍是单副本"

section "2-5 转发头信任"
if ! grep -F -q -x 'jasagent.gateway.forwarded.trusted=${JASAGENT_GATEWAY_FORWARDED_TRUSTED:}' "${props}"; then
  echo "信任名单默认不是空白。" >&2
  exit 1
fi
if ! grep -q '^server.forward-headers-strategy=none$' "${props}"; then
  echo "容器仍可能按私网默认名单信任转发头。" >&2
  exit 1
fi
if ! grep -q '^server.tomcat.use-relative-redirects=true$' "${props}"; then
  echo "容器跳转仍可能被拼成绝对地址。" >&2
  exit 1
fi
if grep -q 'sendRedirect(' "${repo}/jasagent-gateway/src/main/java/dev/jasagent/gateway/auth/GatewaySessionFilter.java"; then
  echo "未登录跳转仍会拼绝对地址。" >&2
  exit 1
fi
pass "只按受信任的上一跳识别 HTTPS，跳转保持相对路径"

section "8 密钥"
# 只扫产品代码和变更记录。测试和本脚本会写出这些前缀，用来拒绝真正的凭证。
if grep -R -n -E 'tskey-|BEGIN PRIVATE KEY|AKIA[0-9A-Z]{16}' \
  "${repo}/jasagent-gateway/src/main" \
  "${repo}/changelog/2026-11-17-gateway-public-ingress.md" >/dev/null; then
  echo "仓库里出现了密钥材料。" >&2
  exit 1
fi
pass "没有写入口令、令牌或 Funnel 凭证"

section "1-8 模块测试"
if ! command -v mvn >/dev/null 2>&1; then
  echo "找不到 mvn。" >&2
  exit 1
fi
mvn -pl jasagent-gateway -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=TrustedProxyListTest,PublicIngressFilterTest,PublicIngressContractTest,DefaultForwardedTrustTest,PublicIngressServerTest
for class in TrustedProxyListTest PublicIngressFilterTest PublicIngressContractTest DefaultForwardedTrustTest PublicIngressServerTest; do
  report="${repo}/jasagent-gateway/target/surefire-reports/dev.jasagent.gateway.ingress.${class}.txt"
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
pass "模块测试"

printf '019 TEST PASSED\n'
