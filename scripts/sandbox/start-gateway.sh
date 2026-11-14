#!/usr/bin/env bash
# 与 scripts/start.sh 使用同一套 18721。
# 这里只从仓库外准备内部令牌，然后交给日常入口拉起网关、控制台上游和 18731、18732 的 agent。
# 不再另外占用 18721，也不再按另一套启动完成日志判断网关是否就绪。
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"
# shellcheck disable=SC1091
source "$JASAGENT_ROOT/scripts/common.sh"
jasagent_ensure_internal_token
jasagent_load_xai_key
exec "$JASAGENT_ROOT/scripts/start.sh"
