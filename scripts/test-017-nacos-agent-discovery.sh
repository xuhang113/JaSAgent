#!/usr/bin/env bash
# 017 的合同测试，覆盖规格验收第 1 到第 12 条。
# 不修改 deploy/k8s，不修改 sidecar 产品代码，也不修改官方探测脚本。
set -euo pipefail

# 仓库根目录。
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo}"

# 最近一次命令的输出。
CAPTURE_TEXT=""

# 最近一次命令的退出码。
CAPTURE_CODE=0

# 本次临时生成的口令，只用来确认输出里没有密文。不写进仓库。
nacos_secret="nacos-pass-$(python3 -c 'import secrets; print(secrets.token_hex(8))')"

# 本次使用的 Nacos 地址。脚本说明和失败输出里不应出现它。
nacos_addr="10.8.7.6:18848"

# 打印一条验收项。
section() {
  printf 'TEST %s\n' "$1"
}

# 打印一条通过。
pass() {
  printf 'PASS %s\n' "$1"
}

# 把本次口令和地址打成占位符后再输出，避免失败日志泄露测试值。
redact() {
  local text="$1"
  text="${text//${nacos_secret}/[redacted]}"
  text="${text//${nacos_addr}/[redacted-addr]}"
  printf '%s\n' "${text}"
}

# 运行命令，把输出和退出码留在 CAPTURE_*。
run_capture() {
  set +e
  CAPTURE_TEXT="$(
    set -euo pipefail
    "$@" 2>&1
  )"
  CAPTURE_CODE=$?
  set -e
}

# 文本里不能出现本次口令。
assert_clean() {
  local text="$1"
  local label="$2"
  if [[ "${text}" == *"${nacos_secret}"* ]]; then
    echo "${label} 出现了密文。" >&2
    redact "${text}" >&2
    exit 1
  fi
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

# 在干净的 HOME 里加载脚本函数。调用方再执行具体函数。
load_funcs() {
  # shellcheck disable=SC1091
  source "${repo}/scripts/common.sh"
}

section "1-12 脚本合同"

assert_comments "${repo}/scripts/start.sh"
assert_comments "${repo}/scripts/stop.sh"
for fn in jasagent_is_loopback_host jasagent_advertise_host jasagent_load_nacos_env jasagent_ensure_nacos jasagent_prepare_nacos jasagent_stop_managed_nacos; do
  if ! grep -B1 -E "^${fn}\\(\\)" "${repo}/scripts/common.sh" | head -n 1 | grep -q '^#'; then
    echo "common.sh 的 ${fn} 缺少注释。" >&2
    exit 1
  fi
done

start_text="$(cat "${repo}/scripts/start.sh")"
if [[ "${start_text}" != *"jasagent_prepare_nacos"* || "${start_text}" != *"--server.address=0.0.0.0"* ]]; then
  echo "start.sh 没有走 Nacos，或 agent 没有监听所有网卡。" >&2
  exit 1
fi
if [[ "${start_text}" == *"jasagent.gateway.agents"* ]]; then
  echo "start.sh 仍把静态目录传给网关。" >&2
  exit 1
fi
if [[ "${start_text}" != *"18731"* || "${start_text}" != *"18732"* || "${start_text}" != *'port="${JASAGENT_PORT:-18721}"'* ]]; then
  echo "本机端口约定丢了。" >&2
  exit 1
fi

spec_base="8587b821d12e616bbfcd3575ba7e472a9dbb1d12"
forbidden="$(git diff --name-only "${spec_base}" -- deploy/k8s jasagent-sidecar scripts/k8s/probe-egress.sh || true)"
untracked="$(git ls-files --others --exclude-standard -- deploy/k8s jasagent-sidecar scripts/k8s/probe-egress.sh || true)"
if [[ -n "${forbidden}${untracked}" ]]; then
  echo "实现碰到了 deploy/k8s、sidecar 或官方探测脚本。" >&2
  printf '%s\n' "${forbidden}" "${untracked}" >&2
  exit 1
fi

home="$(mktemp -d)"
work="$(mktemp -d)"
cleanup() {
  rm -rf "${home}" "${work}"
}
trap cleanup EXIT

export HOME="${home}"
unset JASAGENT_NACOS_SERVER_ADDR JASAGENT_NACOS_SERVER_FILE JASAGENT_NACOS_USERNAME \
  JASAGENT_NACOS_USERNAME_FILE JASAGENT_NACOS_PASSWORD_FILE JASAGENT_NACOS_START \
  JASAGENT_NACOS_HOME JASAGENT_NACOS_STOP JASAGENT_ADVERTISE_HOST || true

cat > "${work}/missing-addr.sh" <<EOF
#!/bin/bash
set -euo pipefail
export HOME='${home}'
unset JASAGENT_NACOS_SERVER_ADDR JASAGENT_NACOS_USERNAME JASAGENT_NACOS_PASSWORD_FILE JASAGENT_ADVERTISE_HOST
source '${repo}/scripts/common.sh'
jasagent_load_nacos_env
EOF
chmod +x "${work}/missing-addr.sh"
run_capture "${work}/missing-addr.sh"
if [[ "${CAPTURE_CODE}" -eq 0 ]]; then
  echo "缺少 Nacos 地址时应该失败。" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "缺少地址"
if [[ "${CAPTURE_TEXT}" != *"缺少 Nacos 地址"* ]]; then
  echo "缺少地址时没有说明原因。" >&2
  redact "${CAPTURE_TEXT}" >&2
  exit 1
fi

printf '%s\n' "${nacos_secret}" > "${work}/password"
printf '%s\n' "${nacos_addr}" > "${work}/address"
cat > "${work}/load-addr.sh" <<EOF
#!/bin/bash
set -euo pipefail
export HOME='${home}'
export JASAGENT_NACOS_SERVER_FILE='${work}/address'
export JASAGENT_NACOS_USERNAME='nacos'
export JASAGENT_NACOS_PASSWORD_FILE='${work}/password'
source '${repo}/scripts/common.sh'
jasagent_load_nacos_env
if [[ "\${JASAGENT_NACOS_SERVER_ADDR}" != '${nacos_addr}' ]]; then
  exit 2
fi
EOF
chmod +x "${work}/load-addr.sh"
run_capture "${work}/load-addr.sh"
if [[ "${CAPTURE_CODE}" -ne 0 ]]; then
  echo "地址文件没有被读入。" >&2
  redact "${CAPTURE_TEXT}" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "读取地址"
if [[ "${CAPTURE_TEXT}" == *"${nacos_addr}"* ]]; then
  echo "读取地址时把地址打出来了。" >&2
  exit 1
fi

cat > "${work}/loopback.sh" <<EOF
#!/bin/bash
set -euo pipefail
export HOME='${home}'
export JASAGENT_ADVERTISE_HOST=127.0.0.1
export JASAGENT_NACOS_PASSWORD_FILE='${work}/password'
source '${repo}/scripts/common.sh'
jasagent_advertise_host
EOF
chmod +x "${work}/loopback.sh"
run_capture "${work}/loopback.sh"
if [[ "${CAPTURE_CODE}" -eq 0 ]]; then
  echo "回环注册地址应该被拒绝。" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "回环地址"
if [[ "${CAPTURE_TEXT}" != *"注册地址不能是回环地址"* ]]; then
  echo "回环地址没有被说明拒绝。" >&2
  redact "${CAPTURE_TEXT}" >&2
  exit 1
fi

ready="${work}/ready"
starter="${work}/start-nacos.sh"
stopper="${work}/stop-nacos.sh"
cat > "${starter}" <<EOF
#!/bin/bash
printf '%s\n' "\$*" > "${work}/starter-args"
if env | grep -F -q '${nacos_secret}'; then
  echo leaked > "${work}/leaked"
fi
touch "${ready}"
exit 0
EOF
cat > "${stopper}" <<EOF
#!/bin/bash
touch "${work}/stopped"
exit 0
EOF
chmod +x "${starter}" "${stopper}"

cat > "${work}/prepare.sh" <<EOF
#!/bin/bash
set -euo pipefail
export HOME='${home}'
export JASAGENT_NACOS_SERVER_ADDR='${nacos_addr}'
export JASAGENT_NACOS_START='${starter}'
export JASAGENT_NACOS_STOP='${stopper}'
export JASAGENT_RUNTIME_DIR='${work}/runtime'
export JASAGENT_ADVERTISE_HOST='10.1.2.3'
source '${repo}/scripts/common.sh'
jasagent_port_open() { [[ -f '${ready}' ]]; }
jasagent_prepare_nacos
[[ "\${JASAGENT_ADVERTISE_HOST}" == '10.1.2.3' ]]
EOF
chmod +x "${work}/prepare.sh"
run_capture "${work}/prepare.sh"
if [[ "${CAPTURE_CODE}" -ne 0 ]]; then
  echo "Nacos 未就绪时没有被拉起。" >&2
  redact "${CAPTURE_TEXT}" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "拉起 Nacos"
if [[ "${CAPTURE_TEXT}" == *"${nacos_addr}"* ]]; then
  echo "拉起 Nacos 时把地址打出来了。" >&2
  exit 1
fi
if [[ -f "${work}/leaked" ]]; then
  echo "启动 Nacos 时环境里出现了口令。" >&2
  exit 1
fi
if [[ ! -f "${work}/runtime/nacos.managed" ]]; then
  echo "没有记下本入口拉起的 Nacos。" >&2
  exit 1
fi
if grep -F -q "${nacos_secret}" "${work}/runtime/nacos.managed" "${work}/starter-args" 2>/dev/null; then
  echo "管理文件或启动参数里有口令。" >&2
  exit 1
fi

cat > "${work}/stop.sh" <<EOF
#!/bin/bash
set -euo pipefail
export HOME='${home}'
export JASAGENT_RUNTIME_DIR='${work}/runtime'
export JASAGENT_NACOS_STOP='${stopper}'
source '${repo}/scripts/common.sh'
jasagent_stop_managed_nacos
EOF
chmod +x "${work}/stop.sh"
run_capture "${work}/stop.sh"
if [[ "${CAPTURE_CODE}" -ne 0 || ! -f "${work}/stopped" ]]; then
  echo "没有停掉本入口拉起的 Nacos。" >&2
  redact "${CAPTURE_TEXT}" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "停止 Nacos"

pass "脚本使用 Nacos，注册地址非回环，密文不出现，且未改 deploy/k8s、sidecar 和 probe-egress.sh"

section "1-12 模块测试"
mvn -pl jasagent-app,jasagent-gateway -am test \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=ReachableAddressTest,NacosClientNamingTest,NacosRegistrationTest,NacosRegistrationSpringTest,ScalingMetricTest,ActiveSessionMetricTest,NacosDiscoveryTest,NacosStickyRoutingTest,NacosContractTest,StickyAgentRouterTest,GatewayForwardTest,GatewayLayoutTest,StartScriptTest,DefaultPortTest,ClusterInventoryTest,StickyProxyTokenTest,GatewayContractTest,TcpAgentLinkTest,ConsoleProxyTest,GatewayAffinityWiringTest
pass "模块测试"

printf '017 TEST PASSED\n'
