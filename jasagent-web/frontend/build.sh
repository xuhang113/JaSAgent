#!/usr/bin/env bash
# 把网关管理控制台写进 jasagent-gateway 的 gateway-admin。
# agent 不扫描 dev.jasagent.gateway，也不会提供这份页面。
# 网关导入 GatewayAdminStaticMount 后，从 classpath:/gateway-admin/ 挂到 /admin/。
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v npm >/dev/null 2>&1; then
  export PATH="/opt/homebrew/bin:/usr/local/bin:${PATH:-}"
fi
if ! command -v npm >/dev/null 2>&1; then
  echo "需要 npm 才能构建控制台页面。" >&2
  exit 1
fi
if [[ ! -d node_modules ]]; then
  npm ci
fi
npm run build
root="$(cd .. && pwd)"
rm -rf "$root/target/classes/static" "$root/target/classes/gateway-admin" "$root/src/main/resources/static" "$root/src/main/resources/gateway-admin"
