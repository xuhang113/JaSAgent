#!/usr/bin/env bash
# 026 的实现侧合同测试，覆盖规格第 1 到第 8 条里仓库内可以判定的项。
# 第 9 到第 12 条是部署阶段，本脚本不覆盖，也不代替 ATDD。
set -euo pipefail

# 仓库根目录。
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo}"

# 本机 Maven 若不在 PATH 上，使用已解压的 3.9.9。不改仓库里的构建文件。
if [[ -x /tmp/apache-maven-3.9.9/bin/mvn ]]; then
  export PATH="/tmp/apache-maven-3.9.9/bin:${PATH}"
fi

# 允许出现在本轮差异里的路径。其它模块出现即失败。
allowed_re='^(jasagent-gateway/|jasagent-web/|jasagent-persist/|scripts/test-026-gateway-session-media\.sh$|changelog/2026-11-26-gateway-session-media\.md$)'
# 仓库外的数据库口令文件。只检查它在不在，不读取、不打印。
password_file="${HOME}/.config/urag-mysql-root.password"
# 相对当前 master 的比较基线。assert_scope 里赋值。
base=""

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

# 确认本机 MySQL 在听。网关测试连 jasagent，起不来就失败。
ensure_mysql() {
  if sudo mysqladmin --socket=/run/mysqld/mysqld.sock ping >/dev/null 2>&1; then
    return 0
  fi
  sudo mkdir -p /run/mysqld
  sudo chown mysql:mysql /run/mysqld
  sudo mysqld --user=mysql --datadir=/var/lib/mysql --bind-address=127.0.0.1 --port=3306 --socket=/run/mysqld/mysqld.sock --pid-file=/run/mysqld/mysqld.pid >/tmp/mariadbd-026.log 2>&1 &
  local i
  for i in $(seq 1 30); do
    if sudo mysqladmin --socket=/run/mysqld/mysqld.sock ping >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  fail "本机 MySQL 没有起来，026 不改用内存库或新建库代替"
}

# 列出相对基线的已改和未跟踪文件。
changed_files() {
  git diff --name-only "${base}"
  git ls-files --others --exclude-standard
}

# 确认差异只在网关、控制台、持久化、本脚本和变更记录。
assert_scope() {
  base="$(git merge-base HEAD origin/master)"
  mapfile -t files < <(changed_files | sed '/^$/d' | sort -u)
  if [[ "${#files[@]}" -eq 0 ]]; then
    fail "相对基线没有差异"
  fi
  local file
  for file in "${files[@]}"; do
    case "${file}" in
      deploy/k8s/*|jasagent-otel/*|jasagent-trace/*|jasagent-sidecar/*|jasagent-agentpass/*|jasagent-agentpass-server/*|agentpass-server/*|jasagent-app/*)
        fail "不允许改动 ${file}"
        ;;
      requirements/*)
        fail "不允许改动需求目录 ${file}"
        ;;
      *027*)
        fail "不允许写 027：${file}"
        ;;
      scripts/k8s/probe-egress.sh|scripts/k8s/prepare-cluster.sh|scripts/up-stack.sh)
        fail "不允许改动 ${file}"
        ;;
      scripts/test-01*.sh|scripts/test-02[0-5]*.sh)
        fail "不允许改动既有测试脚本 ${file}"
        ;;
    esac
    if [[ ! "${file}" =~ ${allowed_re} ]]; then
      fail "路径不在本轮允许范围内：${file}"
    fi
  done
  if ! git diff --quiet "${base}" -- deploy/k8s jasagent-otel jasagent-trace jasagent-sidecar jasagent-agentpass jasagent-agentpass-server jasagent-app scripts/k8s/probe-egress.sh scripts/k8s/prepare-cluster.sh scripts/up-stack.sh; then
    fail "部署清单、otel、trace、sidecar、agentpass、执行端或官方脚本有差异"
  fi
  pass "差异只在 jasagent-gateway、jasagent-web、jasagent-persist、变更记录和本脚本"
}

# 新增行里不能出现历史入口的主机名。名字由两段拼出，避免脚本源码里直接写下它。
assert_no_historical_host() {
  local token="ts.""net"
  if git diff -U0 "${base}" | grep -E '^\+' | grep -F -- "${token}" >/dev/null; then
    fail "新增内容写了不该出现的主机名"
  fi
  pass "新增内容没有历史入口"
}

# 主源码里新增和改动的类、常量、方法都要有注释。不把口令写进 JDBC 地址。
assert_sources() {
  python3 - <<'PY'
from pathlib import Path
import subprocess
base = subprocess.check_output(["git", "merge-base", "HEAD", "origin/master"], text=True).strip()
changed = subprocess.check_output(["git", "diff", "--name-only", base], text=True).splitlines()
untracked = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], text=True).splitlines()
files = [Path(name) for name in changed + untracked if name.endswith(".java") and "/src/main/" in name]
missing = []

def documented(lines, index):
    cursor = index - 1
    while cursor >= 0:
        previous = lines[cursor].strip()
        if previous.endswith("*/"):
            return True
        if previous == "}" or " class " in previous or " interface " in previous or " record " in previous:
            return False
        cursor -= 1
    return False

for path in files:
    lines = path.read_text(encoding="utf-8").splitlines()
    for index, raw in enumerate(lines):
        line = raw.strip()
        if line.startswith(("public class ", "final class ", "public final class ", "class ", "public record ", "private record ", "record ", "public interface ", "interface ")):
            if not documented(lines, index):
                missing.append(f"{path}:{index + 1} {line}")
            continue
        if any(token in line for token in (" class ", " interface ", " record ", " enum ")):
            continue
        constant = "static" in line and "final" in line and "=" in line and "(" not in line.split("=", 1)[0]
        method = line.endswith("{") and "(" in line and ")" in line and "->" not in line and "=" not in line.split("(", 1)[0]
        if method and (line.startswith(("if ", "if(", "for ", "for(", "while ", "while(", "switch ", "catch ", "} catch", "else ", "return ", "throw ", "new ", "try ", "synchronized ")) or line.startswith("}") or " new " in line):
            method = False
        if not method and not constant:
            continue
        if not documented(lines, index):
            missing.append(f"{path}:{index + 1} {line}")
if missing:
    print("\n".join(missing))
    raise SystemExit(1)
PY
  if grep -RIn --include='*.java' --include='*.properties' --include='*.vue' --include='*.js' -E 'jdbc:[^[:space:]]*password=' jasagent-gateway/src/main jasagent-web/src/main jasagent-persist/src/main >/dev/null; then
    fail "主源码或说明里的 JDBC 地址带有 password="
  fi
  if ! grep -q 'jasagent.gateway.web-origin=http://127.0.0.1:18722' jasagent-gateway/src/main/resources/application.properties; then
    fail "控制台上游被改掉了"
  fi
  if ! grep -q 'jasagent.gateway.jaeger-origin=${JASAGENT_GATEWAY_JAEGER_ORIGIN:}' jasagent-gateway/src/main/resources/application.properties; then
    fail "Jaeger 上游缺省没有保持为空"
  fi
  if ! grep -q 'server.forward-headers-strategy=none' jasagent-gateway/src/main/resources/application.properties; then
    fail "转发头策略被改掉了"
  fi
  if ! grep -q 'jasagent.gateway.replicas=1' jasagent-gateway/src/main/resources/application.properties; then
    fail "本机缺省副本数被改掉了"
  fi
  if ! grep -q 'server.port=18721' jasagent-gateway/src/main/resources/application.properties; then
    fail "网关端口被改掉了"
  fi
  pass "主源码有注释，入口和转发口径保持原样"
}

# 跑附件账本，并复跑原来的会话账本测试。
run_persist_tests() {
  local log="/tmp/jasagent-026-persist-test.log"
  mvn -pl jasagent-persist -am test -Dexec.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SessionAttachmentLedgerTest,SessionLedgerMysqlTest | tee "${log}"
  if ! grep -q 'SessionAttachmentLedgerTest' "${log}"; then
    fail "没有跑到附件账本测试"
  fi
  if ! grep -q 'SessionLedgerMysqlTest' "${log}"; then
    fail "没有跑到原有会话账本测试"
  fi
  pass "jasagent-persist 附件和会话账本测试通过"
}

# 跑附件合同，并复跑布局、多副本、转发、Jaeger 和租户隔离。
# 019 的清单断言对着当前 deploy/k8s 副本数，本轮不改那份清单，也不改那个测试。
run_gateway_tests() {
  local log="/tmp/jasagent-026-gateway-test.log"
  mvn -pl jasagent-gateway -am test -Dexec.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=AttachmentMediaTest,AttachmentPathsTest,AttachmentBodyTest,AttachmentFilterTest,SessionMediaGatewayTest,SessionMediaReplicaTest,GatewayLayoutTest,GatewayHaContractTest,GatewayContractTest,JaegerPathsTest,JaegerProxyTest,CEndTenantImTest | tee "${log}"
  if ! grep -q 'AttachmentMediaTest' "${log}"; then
    fail "没有跑到类型判断测试"
  fi
  if ! grep -q 'SessionMediaGatewayTest' "${log}"; then
    fail "没有跑到网关附件测试"
  fi
  if ! grep -q 'SessionMediaReplicaTest' "${log}"; then
    fail "没有跑到跨进程取回测试"
  fi
  if ! grep -q 'GatewayLayoutTest' "${log}"; then
    fail "没有跑到网关布局测试"
  fi
  if ! grep -q 'JaegerProxyTest' "${log}"; then
    fail "没有跑到 Jaeger 反代测试"
  fi
  if ! grep -q 'CEndTenantImTest' "${log}"; then
    fail "没有跑到既有租户隔离测试"
  fi
  pass "jasagent-gateway 自动测试通过"
}

# 跑页面入口的源码合同和前端测试。
run_web_tests() {
  local log="/tmp/jasagent-026-web-test.log"
  mvn -pl jasagent-web -am test -Dexec.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=JaegerConsoleLinkTest | tee "${log}"
  if ! grep -q 'JaegerConsoleLinkTest' "${log}"; then
    fail "没有跑到控制台链接测试"
  fi
  if [[ ! -d jasagent-web/frontend/node_modules ]]; then
    npm ci --prefix jasagent-web/frontend
  fi
  npm test --prefix jasagent-web/frontend
  pass "jasagent-web 链接和页面测试通过"
}

section "注释、差异范围和主源码口径"
assert_comments "${repo}/scripts/test-026-gateway-session-media.sh"
assert_scope
assert_no_historical_host
assert_sources
section "本机 MySQL 与自动测试"
if [[ ! -f "${password_file}" ]]; then
  fail "缺少仓库外的数据库口令文件，测试不在仓库里写口令"
fi
ensure_mysql
run_persist_tests
run_gateway_tests
run_web_tests
section "完成"
pass "026 网关会话附件"
