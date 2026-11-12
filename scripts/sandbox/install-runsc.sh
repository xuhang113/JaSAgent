#!/usr/bin/env bash
# 在 Colima 的 Linux 虚拟机里把已有的 runsc 登记成 Docker 运行时。默认运行时仍是 runc。
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

if ! command -v colima >/dev/null 2>&1; then
  echo "需要 Colima。当前没有 colima 命令，没法在 Linux 虚拟机里装 runsc。" >&2
  exit 1
fi
if ! colima status >/dev/null 2>&1; then
  echo "Colima 没有在运行。" >&2
  exit 1
fi

if jasagent_docker_has_runsc; then
  echo "Docker 已经登记 runsc。"
  docker info --format 'DefaultRuntime={{.DefaultRuntime}}'
  exit 0
fi

if ! colima ssh -- test -x /usr/local/bin/runsc; then
  echo "虚拟机里没有 /usr/local/bin/runsc。开始下载 gVisor runsc。" >&2
  colima ssh -- bash -lc 'set -euo pipefail
    arch=$(uname -m)
    url="https://storage.googleapis.com/gvisor/releases/release/latest/${arch}"
    tmp=$(mktemp -d)
    cd "$tmp"
    curl -fsSL -o runsc "${url}/runsc"
    curl -fsSL -o runsc.sha512 "${url}/runsc.sha512"
    curl -fsSL -o containerd-shim-runsc-v1 "${url}/containerd-shim-runsc-v1"
    curl -fsSL -o containerd-shim-runsc-v1.sha512 "${url}/containerd-shim-runsc-v1.sha512"
    sha512sum -c runsc.sha512
    sha512sum -c containerd-shim-runsc-v1.sha512
    sudo install -m 755 runsc containerd-shim-runsc-v1 /usr/local/bin
    rm -rf "$tmp"
  '
fi

recorded="$(mktemp)"
docker ps --format '{{.Names}}' > "$recorded"

colima ssh -- sudo python3 -c 'import json; p="/etc/docker/daemon.json"; cfg=json.load(open(p)); cfg.setdefault("runtimes", {})["runsc"]={"path": "/usr/local/bin/runsc"}; cfg.pop("default-runtime", None); f=open(p,"w"); json.dump(cfg, f, indent=2); f.write("\n"); f.close()'

colima ssh -- sudo systemctl restart docker

ready=0
for _ in $(seq 1 60); do
  if docker info >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
if [[ "$ready" -ne 1 ]]; then
  echo "重启 Docker 后连不上守护进程。" >&2
  rm -f "$recorded"
  exit 1
fi

while IFS= read -r name; do
  [[ -n "$name" ]] || continue
  if ! docker ps --format '{{.Names}}' | grep -qx "$name"; then
    docker start "$name" >/dev/null || true
  fi
done < "$recorded"
rm -f "$recorded"

if ! jasagent_docker_has_runsc; then
  echo "未通过：重启后 Docker 仍然没有 runsc。" >&2
  exit 1
fi
default_runtime="$(docker info --format '{{.DefaultRuntime}}')"
if [[ "$default_runtime" != "runc" ]]; then
  echo "未通过：默认运行时变成了 ${default_runtime}。agent 以外的容器不应被改成 runsc。" >&2
  exit 1
fi
echo "runsc 已登记。DefaultRuntime=${default_runtime}"
docker info --format '{{json .Runtimes}}' | python3 -c 'import json,sys; d=json.load(sys.stdin); print("runtimes=" + ",".join(sorted(d)))'
