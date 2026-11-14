#!/usr/bin/env bash
# 验收 015：跑网关、控制台上游和端口相关测试，并检查缺少 Java 21、网关 jar 或上游时启动脚本以非 0 退出。
# 标准输出和标准错误里不应出现口令或内部令牌。本脚本不修改 deploy/k8s。
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo"

if git diff --name-only HEAD -- deploy/k8s | grep -q .; then
  echo "实现和测试不应修改 deploy/k8s。" >&2
  exit 1
fi

mvn -pl jasagent-gateway,jasagent-web,jasagent-app -am test \
  -Dtest=ConsoleProxyTest,GatewayForwardTest,GatewayAffinityWiringTest,AffinityJdbcTest,StickyProxyTokenTest,GatewayLayoutTest,GatewayContractTest,StickyAgentRouterTest,ClusterInventoryTest,TcpAgentLinkTest,StartScriptTest,WebUpstreamTest,DefaultPortTest,ConsolePageTest#loginPageReferencesVueBuild \
  -Dsurefire.failIfNoSpecifiedTests=false

mvn -pl jasagent-gateway,jasagent-web -am package -DskipTests
gateway_jar="$(find jasagent-gateway/target -maxdepth 1 -type f -name 'jasagent-gateway-*.jar' ! -name '*.jar.original' | sort | tail -n 1)"
web_jar="$(find jasagent-web/target -maxdepth 1 -type f -name 'jasagent-web-*-exec.jar' | sort | tail -n 1)"
if [[ -z "$gateway_jar" || -z "$web_jar" ]]; then
  echo "打包后找不到网关 jar 或控制台上游。" >&2
  exit 1
fi
if jar tf "$gateway_jar" | grep -q 'gateway-admin'; then
  echo "网关包里仍有 gateway-admin。" >&2
  exit 1
fi
if ! jar tf "$web_jar" | grep -q 'admin/index.html'; then
  echo "控制台上游包里没有登录页。" >&2
  exit 1
fi

token="super-secret-token-value"
db_secret="super-secret-db-password"
fail_if_leak() {
  local text="$1"
  if [[ "$text" == *"$token"* || "$text" == *"$db_secret"* ]]; then
    echo "启动失败说明里出现了口令或令牌。" >&2
    exit 1
  fi
}

run_start() {
  local path="$1"
  local root="$2"
  local home="$3"
  set +e
  local text
  text="$(PATH="$path:/usr/bin:/bin" HOME="$home" JASAGENT_ROOT="$root" JASAGENT_RUNTIME_DIR="$home/runtime" \
    JASAGENT_INTERNAL_TOKEN="$token" JASAGENT_CHECKPOINT_JDBC_PASSWORD="$db_secret" \
    bash "$repo/scripts/start.sh" 2>&1)"
  local code=$?
  set -e
  printf '%s\n' "$code"
  printf '%s\n' "$text"
}

java17="$(mktemp -d)"
cat > "$java17/java" <<'EOF'
#!/bin/bash
echo 'openjdk version "17.0.9" 2023-10-17' >&2
exit 0
EOF
chmod +x "$java17/java"
java17_out="$(run_start "$java17" "$(mktemp -d)" "$(mktemp -d)")"
java17_code="$(printf '%s\n' "$java17_out" | head -n 1)"
java17_text="$(printf '%s\n' "$java17_out" | tail -n +2)"
if [[ "$java17_code" -eq 0 ]]; then
  echo "Java 17 仍启动成功。" >&2
  exit 1
fi
if [[ "$java17_text" != *"Java 21"* ]]; then
  echo "缺少 Java 21 时没有说明版本要求。" >&2
  exit 1
fi
fail_if_leak "$java17_text"

java21="$(mktemp -d)"
cat > "$java21/java" <<'EOF'
#!/bin/bash
if [[ "${1:-}" == "-version" ]]; then
  echo 'openjdk version "21.0.1" 2026-01-01' >&2
  exit 0
fi
echo "不应该启动 Java 进程。" >&2
exit 99
EOF
cat > "$java21/mvn" <<'EOF'
#!/bin/bash
exit 0
EOF
chmod +x "$java21/java" "$java21/mvn"
missing_root="$(mktemp -d)"
missing_out="$(run_start "$java21" "$missing_root" "$(mktemp -d)")"
missing_code="$(printf '%s\n' "$missing_out" | head -n 1)"
missing_text="$(printf '%s\n' "$missing_out" | tail -n +2)"
if [[ "$missing_code" -eq 0 ]]; then
  echo "缺少网关 jar 时仍成功退出。" >&2
  exit 1
fi
if [[ "$missing_text" != *"找不到网关 jar。"* ]]; then
  echo "缺少网关 jar 时没有对应说明。" >&2
  exit 1
fi
fail_if_leak "$missing_text"

cat > "$java21/mvn" <<'EOF'
#!/bin/bash
mkdir -p "$JASAGENT_ROOT/jasagent-gateway/target"
: > "$JASAGENT_ROOT/jasagent-gateway/target/jasagent-gateway-0.1.0.jar"
exit 0
EOF
upstream_root="$(mktemp -d)"
upstream_out="$(run_start "$java21" "$upstream_root" "$(mktemp -d)")"
upstream_code="$(printf '%s\n' "$upstream_out" | head -n 1)"
upstream_text="$(printf '%s\n' "$upstream_out" | tail -n +2)"
if [[ "$upstream_code" -eq 0 ]]; then
  echo "缺少控制台上游时仍成功退出。" >&2
  exit 1
fi
if [[ "$upstream_text" != *"找不到控制台上游。"* ]]; then
  echo "缺少控制台上游时没有对应说明。" >&2
  exit 1
fi
fail_if_leak "$upstream_text"

echo "015 网关测试通过。"
