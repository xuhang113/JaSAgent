#!/usr/bin/env bash
# 按顺序跑 023 ATDD 第 1 到第 9 条。不调用官方合同脚本。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
for case in T-001 T-002 T-003 T-004 T-005 T-006 T-007 T-008 T-009; do
  "${HERE}/${case}"
done
