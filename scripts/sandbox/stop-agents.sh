#!/usr/bin/env bash
# 停掉本机两台 runsc agent。
set -euo pipefail
for name in jasagent-agent-1 jasagent-agent-2; do
  if docker inspect "$name" >/dev/null 2>&1; then
    docker rm -f "$name" >/dev/null
  fi
done
