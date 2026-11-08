#!/usr/bin/env bash
# 把当前 Vue 页面写进 src/main/resources/static，供 mvn package 打进 jar。
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
rm -rf "$(cd .. && pwd)/target/classes/static"
