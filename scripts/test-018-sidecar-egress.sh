#!/usr/bin/env bash
# 018 的合同测试，覆盖规格验收第 1 到第 9 条。
# 不修改 deploy/k8s，不修改官方探测脚本，也不改网关和 jasagent-web。
# 第 10 到第 15 条是部署阶段，本脚本不覆盖。
set -euo pipefail

# 仓库根目录。
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo}"

# 018 规格提交。实现相对它不得改动部署清单和官方探测。
spec_base="08be62c33ec4c0571e99943215cb2602e810745c"

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
  local prev="" line n=0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    n=$((n + 1))
    if [[ "${line}" =~ ^[a-zA-Z_][a-zA-Z0-9_]*\(\)[[:space:]]*\{$ || "${line}" =~ ^[A-Z][A-Z0-9_]+= ]]; then
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

section "1-9 出站路径和范围"

assert_comments "${repo}/scripts/start.sh"
assert_comments "${repo}/scripts/stop.sh"
for fn in jasagent_build_stack jasagent_ensure_stack_jars; do
  if ! grep -B1 -E "^${fn}\\(\\)" "${repo}/scripts/common.sh" | head -n 1 | grep -q '^#'; then
    echo "common.sh 的 ${fn} 缺少注释。" >&2
    exit 1
  fi
done

start_text="$(cat "${repo}/scripts/start.sh")"
stop_text="$(cat "${repo}/scripts/stop.sh")"
if [[ "${start_text}" != *"jasagent_start_sidecar"* || "${start_text}" != *"JASAGENT_SIDECAR_URL"* ]]; then
  echo "start.sh 没有拉起 sidecar，或没有把同一地址交给 agent。" >&2
  exit 1
fi
if [[ "${start_text}" != *"18731"* || "${start_text}" != *"18732"* || "${start_text}" != *"--server.address=127.0.0.1"* ]]; then
  echo "本机 sidecar 不是回环监听，或两台 agent 的端口约定丢了。" >&2
  exit 1
fi
if [[ "${start_text}" != *"/v1/forward"* ]]; then
  echo "启动说明没有写明 /v1/forward。" >&2
  exit 1
fi
if [[ "${stop_text}" != *"JASAGENT_SIDECAR_PID_FILE"* ]]; then
  echo "stop.sh 没有停下 sidecar。" >&2
  exit 1
fi

common_text="$(cat "${repo}/scripts/common.sh")"
if [[ "${common_text}" != *'JASAGENT_SIDECAR_PORT="${JASAGENT_SIDECAR_PORT:-18761}"'* \
  || "${common_text}" != *'http://127.0.0.1:${JASAGENT_SIDECAR_PORT}'* ]]; then
  echo "两台 agent 没有共用默认的回环 sidecar。" >&2
  exit 1
fi

if grep -R -n -E 'readAllBytes|ofByteArray' "${repo}/jasagent-sidecar/src/main" >/dev/null; then
  echo "sidecar 转发仍在整包读取正文。" >&2
  grep -R -n -E 'readAllBytes|ofByteArray' "${repo}/jasagent-sidecar/src/main" >&2
  exit 1
fi
if ! grep -R -n '/v1/forward' "${repo}/jasagent-llm/src/main" "${repo}/jasagent-app/src/main" >/dev/null; then
  echo "agent 或大模型没有走 /v1/forward。" >&2
  exit 1
fi
if grep -R -n '/v1/forward' "${repo}/jasagent-app/src/main/java/dev/jasagent/app/nacos" >/dev/null; then
  echo "Nacos 注册或发现被送进了 sidecar。" >&2
  exit 1
fi
if ! grep -n '注册地址不能是回环地址' "${repo}/jasagent-app/src/main/java/dev/jasagent/app/nacos/ReachableAddress.java" >/dev/null; then
  echo "向 Nacos 注册的地址不再拒绝回环。" >&2
  exit 1
fi

forbidden="$(git diff --name-only "${spec_base}" -- deploy/k8s scripts/k8s/probe-egress.sh jasagent-web jasagent-gateway || true)"
untracked="$(git ls-files --others --exclude-standard -- deploy/k8s scripts/k8s/probe-egress.sh jasagent-web jasagent-gateway || true)"
if [[ -n "${forbidden}${untracked}" ]]; then
  echo "实现碰到了 deploy/k8s、官方探测、网关或 jasagent-web。" >&2
  printf '%s\n' "${forbidden}" "${untracked}" >&2
  exit 1
fi

if grep -R -n -E 'BEGIN PRIVATE KEY|AKIA[0-9A-Z]{16}' \
  "${repo}/jasagent-sidecar/src/main" "${repo}/jasagent-llm/src/main/java/dev/jasagent/llm/egress" \
  "${repo}/jasagent-app/src/main/java/dev/jasagent/app" "${repo}/scripts/start.sh" \
  "${repo}/scripts/stop.sh" "${repo}/scripts/common.sh" "${repo}/changelog/2026-11-15-sidecar-egress.md" \
  >/dev/null; then
  echo "仓库里出现了密钥材料。" >&2
  exit 1
fi

pass "离开集群走 /v1/forward，集群内服务直连，清单未被修改"

section "1-9 模块测试"
if ! command -v mvn >/dev/null 2>&1; then
  echo "找不到 mvn。" >&2
  exit 1
fi
mvn -pl jasagent-sidecar,jasagent-llm,jasagent-app -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=SidecarEgressTest,SidecarHealthTest,SidecarContractTest,SidecarStreamTest,TicketWireTest,LlmAllowlistTest,LlmRouterTest,StreamingThinkingTest,ReasoningTextTest,ToolCallTextTest,EgressRouteTest,SidecarLlmTransportTest,LeavingClusterHttpTest,SessionModelCatalogTest,ReachableAddressTest,NacosRegistrationTest,NacosClientNamingTest,DefaultPortTest
pass "模块测试"

printf '018 TEST PASSED\n'
