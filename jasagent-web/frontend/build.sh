#!/usr/bin/env bash
# 构建控制台页面到 jasagent-web 的 classpath:/admin/。
# 页面源码只留在本模块。不把产物写进 jasagent-gateway。
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
rm -rf "$root/../jasagent-gateway/src/main/resources/gateway-admin" \
  "$root/src/main/resources/static" \
  "$root/src/main/resources/gateway-admin"
