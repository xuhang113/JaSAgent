#!/usr/bin/env bash
# 在本机编译 agent 进程级出站过滤库（LD_PRELOAD）。
# 这台云电脑的内核没有 xt_owner，gVisor 同 Pod 容器还共用一个 hostinet sandbox，
# 按 Pod IP 或 sandbox cgroup 过滤会误伤 sidecar。此库只给 agent 容器预加载。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="${JASAGENT_EGRESS_FILTER_SRC:-${ROOT}/scripts/k8s/jasagent-egress-filter.c}"
DEST="${JASAGENT_EGRESS_FILTER_SO:-/home/box/lib/libjasagent-egress-filter.so}"
if [[ ! -f "${SRC}" ]]; then
  echo "找不到过滤库源码：${SRC}" >&2
  exit 1
fi
if ! command -v gcc >/dev/null 2>&1; then
  echo "需要 gcc 才能编译出站过滤库。" >&2
  exit 1
fi
mkdir -p "$(dirname "${DEST}")"
tmp="$(mktemp "${DEST}.XXXXXX")"
gcc -shared -fPIC -O2 -o "${tmp}" "${SRC}" -ldl
chmod 755 "${tmp}"
mv -f "${tmp}" "${DEST}"
echo "installed ${DEST}"
