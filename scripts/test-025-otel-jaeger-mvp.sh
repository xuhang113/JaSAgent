#!/usr/bin/env bash
# 025 的实现侧合同测试，覆盖规格第 1 到第 8 条里仓库内可以判定的项。
# 第 9 到第 13 条是部署阶段，本脚本不覆盖，也不代替 ATDD。
set -euo pipefail

# 仓库根目录。
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo}"

# 本机 Maven 若不在 PATH 上，使用已解压的 3.9.9。不改仓库里的构建文件。
if [[ -x /tmp/apache-maven-3.9.9/bin/mvn ]]; then
  export PATH="/tmp/apache-maven-3.9.9/bin:${PATH}"
fi

# 允许出现在本轮差异里的路径。其它模块出现即失败。
allowed_re='^(jasagent-app/|jasagent-gateway/|jasagent-web/|scripts/test-025-otel-jaeger-mvp\.sh$|changelog/2026-11-24-otel-jaeger-mvp\.md$)'
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

# 确认本机 MySQL 在听。网关测试连 jasagent，起不来就失败。
ensure_mysql() {
  if sudo mysqladmin --socket=/run/mysqld/mysqld.sock ping >/dev/null 2>&1; then
    return 0
  fi
  sudo mkdir -p /run/mysqld
  sudo chown mysql:mysql /run/mysqld
  sudo mysqld --user=mysql --datadir=/var/lib/mysql --bind-address=127.0.0.1 --port=3306 --socket=/run/mysqld/mysqld.sock --pid-file=/run/mysqld/mysqld.pid >/tmp/mariadbd-025.log 2>&1 &
  local i
  for i in $(seq 1 30); do
    if sudo mysqladmin --socket=/run/mysqld/mysqld.sock ping >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  fail "本机 MySQL 没有起来，025 不改用内存库或新建库代替"
}

# 列出相对基线的已改和未跟踪文件。
changed_files() {
  git diff --name-only "${base}"
  git ls-files --others --exclude-standard
}

# 确认差异只在三个产品模块、本脚本和变更记录。
assert_scope() {
  base="$(git merge-base HEAD origin/master)"
  mapfile -t files < <(changed_files | sed '/^$/d' | sort -u)
  if [[ "${#files[@]}" -eq 0 ]]; then
    fail "相对基线没有差异"
  fi
  local file
  for file in "${files[@]}"; do
    case "${file}" in
      deploy/k8s/*|jasagent-otel/*|jasagent-trace/*|jasagent-sidecar/*|jasagent-agentpass/*|jasagent-agentpass-server/*|agentpass-server/*)
        fail "不允许改动 ${file}"
        ;;
      requirements/025-otel-jaeger-mvp/*)
        fail "不允许改动需求目录 ${file}"
        ;;
      scripts/k8s/probe-egress.sh|scripts/k8s/prepare-cluster.sh|scripts/up-stack.sh)
        fail "不允许改动 ${file}"
        ;;
      scripts/test-01*.sh|scripts/test-02[0-4]*.sh)
        fail "不允许改动既有测试脚本 ${file}"
        ;;
    esac
    if [[ ! "${file}" =~ ${allowed_re} ]]; then
      fail "路径不在本轮允许范围内：${file}"
    fi
  done
  if ! git diff --quiet "${base}" -- deploy/k8s jasagent-otel jasagent-trace jasagent-sidecar jasagent-agentpass jasagent-agentpass-server scripts/k8s/probe-egress.sh scripts/k8s/prepare-cluster.sh scripts/up-stack.sh requirements/025-otel-jaeger-mvp; then
    fail "部署清单、otel、trace、sidecar、agentpass、官方脚本或需求目录有差异"
  fi
  pass "差异只在 jasagent-app、jasagent-gateway、jasagent-web、变更记录和本脚本"
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
        method = line.endswith("{") and "(" in line and ")" in line and "->" not in line
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
  if grep -RIn --include='*.java' --include='*.properties' --include='*.vue' --include='*.js' -E 'jdbc:[^[:space:]]*password=' jasagent-app/src/main jasagent-gateway/src/main jasagent-web/src/main >/dev/null; then
    fail "主源码或说明里的 JDBC 地址带有 password="
  fi
  if ! grep -q 'jasagent.otel.endpoint=${JASAGENT_OTEL_ENDPOINT:}' jasagent-app/src/main/resources/application.properties; then
    fail "执行端点缺省没有保持为空"
  fi
  if ! grep -q 'jasagent.gateway.jaeger-origin=${JASAGENT_GATEWAY_JAEGER_ORIGIN:}' jasagent-gateway/src/main/resources/application.properties; then
    fail "Jaeger 上游缺省没有保持为空"
  fi
  if ! grep -q 'jasagent.gateway.web-origin=http://127.0.0.1:18722' jasagent-gateway/src/main/resources/application.properties; then
    fail "控制台上游被改掉了"
  fi
  pass "主源码有注释，端点缺省为空"
}

# 跑执行导出和既有恢复测试。依赖模块只编译。前端打包不在这里做。
run_app_tests() {
  local log="/tmp/jasagent-025-app-test.log"
  mvn -pl jasagent-app -am test -Dexec.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OtelJaegerExportTest,RestartResumeTest | tee "${log}"
  if ! grep -q 'OtelJaegerExportTest' "${log}"; then
    fail "没有跑到执行链路导出测试"
  fi
  if ! grep -q 'RestartResumeTest' "${log}"; then
    fail "没有跑到原有恢复测试"
  fi
  pass "jasagent-app 导出和恢复测试通过"
}

# 跑网关的 Jaeger 合同，并复跑既有布局和多副本合同。
run_gateway_tests() {
  local log="/tmp/jasagent-025-gateway-test.log"
  mvn -pl jasagent-gateway -am test -Dexec.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=JaegerPathsTest,JaegerProxyTest,GatewayHaContractTest,GatewayLayoutTest | tee "${log}"
  if ! grep -q 'JaegerPathsTest' "${log}"; then
    fail "没有跑到 Jaeger 前缀测试"
  fi
  if ! grep -q 'JaegerProxyTest' "${log}"; then
    fail "没有跑到 Jaeger 反代测试"
  fi
  if ! grep -q 'GatewayLayoutTest' "${log}"; then
    fail "没有跑到网关布局测试"
  fi
  if ! grep -q 'GatewayHaContractTest' "${log}"; then
    fail "没有跑到既有网关合同测试"
  fi
  pass "jasagent-gateway 自动测试通过"
}

# 跑页面入口的源码合同和前端测试。
run_web_tests() {
  local log="/tmp/jasagent-025-web-test.log"
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
assert_comments "${repo}/scripts/test-025-otel-jaeger-mvp.sh"
assert_scope
assert_sources
section "本机 MySQL 与自动测试"
if [[ ! -f "${password_file}" ]]; then
  fail "缺少仓库外的数据库口令文件，测试不在仓库里写口令"
fi
ensure_mysql
run_app_tests
run_gateway_tests
run_web_tests
section "完成"
pass "025 执行链路导出与 admin 反代 Jaeger"
