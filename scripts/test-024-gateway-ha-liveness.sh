#!/usr/bin/env bash
# 024 的网关合同测试，覆盖规格第 1 到第 6 条里依赖网关代码的项。
# 两个副本读写已有库 jasagent 的同一张存活表。不新建库，不改 deploy/k8s。
# 第 7 到第 12 条是部署阶段，本脚本不覆盖，也不代替 ATDD。
set -euo pipefail

# 仓库根目录。
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo}"

# 本机 Maven 若不在 PATH 上，使用已解压的 3.9.9。不改仓库里的构建文件。
if [[ -x /tmp/apache-maven-3.9.9/bin/mvn ]]; then
  export PATH="/tmp/apache-maven-3.9.9/bin:${PATH}"
fi

# 允许出现在本轮差异里的路径。其它模块出现即失败。
allowed_re='^(jasagent-gateway/|scripts/test-024-gateway-ha-liveness\.sh$|changelog/2026-11-22-gateway-ha-liveness\.md$)'
# 旧的单副本句子。网关主源码里不能再出现。部署清单本轮不改，不在这里检查。
outdated='登录会话和存活探测都在网关进程内，本轮网关按单副本验收。'
# 仓库外的数据库口令文件。只检查它在不在，不读取、不打印。
password_file="${HOME}/.config/urag-mysql-root.password"

# 打印一条验收项。
section() {
  printf 'TEST %s\n' "$1"
}

# 打印一条通过。
pass() {
  printf 'PASS %s\n' "$1"
}

# 打印原因并退出。不接收口令、令牌或密钥。
fail() {
  printf 'FAIL %s\n' "$1" >&2
  exit 1
}

# 函数的上一行必须是注释。
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

# 确认本机 MySQL 在听。存活和会话测试都连 jasagent，起不来就失败。
ensure_mysql() {
  if sudo mysqladmin --socket=/run/mysqld/mysqld.sock ping >/dev/null 2>&1; then
    return 0
  fi
  sudo mkdir -p /run/mysqld
  sudo chown mysql:mysql /run/mysqld
  sudo mysqld --user=mysql --datadir=/var/lib/mysql --bind-address=127.0.0.1 --port=3306 --socket=/run/mysqld/mysqld.sock --pid-file=/run/mysqld/mysqld.pid >/tmp/mariadbd-024.log 2>&1 &
  local i
  for i in $(seq 1 30); do
    if sudo mysqladmin --socket=/run/mysqld/mysqld.sock ping >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  fail "本机 MySQL 没有起来，024 不改用内存库或新建库代替"
}

# 列出相对基线的已改和未跟踪文件。
changed_files() {
  git diff --name-only "${base}"
  git ls-files --others --exclude-standard
}

# 确认差异只在网关、本脚本和变更记录。
assert_scope() {
  base="$(git merge-base HEAD origin/master)"
  mapfile -t files < <(changed_files | sed '/^$/d' | sort -u)
  if [[ "${#files[@]}" -eq 0 ]]; then
    fail "相对基线没有差异"
  fi
  local file
  for file in "${files[@]}"; do
    case "${file}" in
      deploy/k8s/*|jasagent-web/*|jasagent-sidecar/*|jasagent-agentpass/*|jasagent-agentpass-server/*|agentpass-server/*)
        fail "不允许改动 ${file}"
        ;;
      scripts/k8s/probe-egress.sh|scripts/k8s/prepare-cluster.sh|scripts/up-stack.sh)
        fail "不允许改动 ${file}"
        ;;
      scripts/test-01*.sh|scripts/test-02[0-3]*.sh)
        fail "不允许改动既有测试脚本 ${file}"
        ;;
    esac
    if [[ ! "${file}" =~ ${allowed_re} ]]; then
      fail "路径不在本轮允许范围内：${file}"
    fi
  done
  if ! git diff --quiet "${base}" -- deploy/k8s jasagent-web jasagent-sidecar jasagent-agentpass jasagent-agentpass-server scripts/k8s/probe-egress.sh scripts/k8s/prepare-cluster.sh scripts/up-stack.sh; then
    fail "部署清单、web、sidecar、agentpass 或官方脚本有差异"
  fi
  pass "差异只在 jasagent-gateway、变更记录和本脚本"
}

# 确认主源码已改掉单副本句子，JDBC 地址仍不带 password=，转发头默认仍不是全网段。
assert_sources() {
  local hit
  hit="$(grep -RIn --include='*.java' --include='*.properties' -F "${outdated}" jasagent-gateway/src/main || true)"
  if [[ -n "${hit}" ]]; then
    fail "网关主源码仍写着存活探测只在本进程"
  fi
  if grep -RIn --include='*.java' --include='*.properties' -E 'jdbc:[^[:space:]]*password=' jasagent-gateway/src/main >/dev/null; then
    fail "网关主源码的 JDBC 地址带有 password="
  fi
  if ! grep -q 'jasagent.gateway.replicas=1' jasagent-gateway/src/main/resources/application.properties; then
    fail "本机缺省副本数被改掉了"
  fi
  if ! grep -q 'jasagent.gateway.forwarded.trusted=${JASAGENT_GATEWAY_FORWARDED_TRUSTED:}' jasagent-gateway/src/main/resources/application.properties; then
    fail "转发头信任不再只从 Ingress 这一跳的环境变量读取"
  fi
  if grep -E '^jasagent\.gateway\.forwarded\.trusted=.*0\.0\.0\.0/0' jasagent-gateway/src/main/resources/application.properties >/dev/null; then
    fail "转发头信任被写成了 0.0.0.0/0"
  fi
  if grep -E '^jasagent\.gateway\.forwarded\.trusted=.*::/0' jasagent-gateway/src/main/resources/application.properties >/dev/null; then
    fail "转发头信任被写成了 ::/0"
  fi
  if ! grep -q '事件流不在这里改写，避免把长连接响应整段读进内存' jasagent-gateway/src/main/java/dev/jasagent/gateway/route/GatewayPaths.java; then
    fail "事件流路径不再声明避免整段读入内存"
  fi
  if ! grep -q 'return MysqlAgentLiveness.open' jasagent-gateway/src/main/java/dev/jasagent/gateway/config/GatewayConfiguration.java; then
    fail "生产装配没有打开共享存活表"
  fi
  pass "主源码口径、JDBC 和事件流约束仍在"
}

# 跑网关自动测试。覆盖共享存活、选路、事件流、转发头、租户隔离和既有网关合同。
# 依赖模块只编译，不跑它们的测试，避免把无关模块的库方言失败算进本轮。
run_tests() {
  if [[ ! -f "${password_file}" ]]; then
    fail "缺少仓库外的数据库口令文件，测试不在仓库里写口令"
  fi
  local log="/tmp/jasagent-024-gateway-test.log"
  mvn -pl jasagent-gateway -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='dev.jasagent.gateway.**' | tee "${log}"
  if ! grep -q 'SharedAgentLivenessTest' "${log}"; then
    fail "没有跑到两个副本的共享存活测试"
  fi
  if ! grep -q 'GatewayHaContractTest' "${log}"; then
    fail "没有跑到事件流和转发头合同测试"
  fi
  if ! grep -q 'CEndTenantImTest' "${log}"; then
    fail "没有跑到租户隔离和非 admin 集群测试"
  fi
  if ! grep -q 'Tests run:' "${log}"; then
    fail "网关测试没有实际执行"
  fi
  pass "jasagent-gateway 自动测试通过"
}

section "注释、差异范围和主源码口径"
assert_comments "${repo}/scripts/test-024-gateway-ha-liveness.sh"
assert_scope
assert_sources
section "本机 MySQL 与网关自动测试"
ensure_mysql
run_tests
section "完成"
pass "024 网关存活探测跨副本"
