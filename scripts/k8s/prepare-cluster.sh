#!/usr/bin/env bash
# 在部署用的 Linux 云电脑上准备 k3s 和 gVisor（runsc）。
# 按 print_fixed_steps 里的固定步骤执行。手工搭集群不是这条路径。
# 二进制和镜像都在当前机器上取得，不从别的电脑拷贝。
#
# 用法：
#   bash scripts/k8s/prepare-cluster.sh
#   bash scripts/k8s/prepare-cluster.sh --steps
#   bash scripts/k8s/prepare-cluster.sh --help
set -euo pipefail

# 脚本所在仓库。RuntimeClass 清单从这里找，不写死某台电脑的家目录。
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# 本机 k3s 的 kubeconfig。调用方已经设置 KUBECONFIG 时不覆盖。
KUBECONFIG_DEFAULT="/etc/rancher/k3s/k3s.yaml"

# runsc 和 containerd shim 的安装位置。gvisor-bin 必须跟 runsc 放在同一目录。
RUNSC_BIN="/usr/local/bin/runsc"

# containerd 调用的 shim。跟 runsc 一起从发布包里解出来。
RUNSC_SHIM="/usr/local/bin/containerd-shim-runsc-v1"

# k3s 读取 containerd 配置的目录。直接改生成后的 config.toml 会被覆盖，所以写模板。
CONTAINERD_DIR="/var/lib/rancher/k3s/agent/etc/containerd"

# gVisor 发布包根地址。后面接本机 uname -m，再接压缩包文件名。
GVISOR_RELEASE_BASE="https://storage.googleapis.com/gvisor/releases/release/latest"

# 打印一行说明。这里不输出口令、令牌或模型密钥。
say() {
  printf '%s\n' "$*"
}

# 打印用法。不连接集群。
usage() {
  cat <<'EOF'
用法：bash scripts/k8s/prepare-cluster.sh
按固定步骤在当前 Linux 机器上准备 k3s 和 gVisor（runsc）。
查看步骤：bash scripts/k8s/prepare-cluster.sh --steps
EOF
}

# 集群准备的固定步骤。云电脑按这个顺序执行，不把手工搭集群当成路径。
print_fixed_steps() {
  cat <<'EOF'
1. 确认当前机器是 Linux，并且可以用 root 或 sudo。
2. 在当前机器上下载 gVisor 发布包，校验 sha512，把 runsc、containerd-shim-runsc-v1 和 gvisor-bin 装到 /usr/local/bin。
3. 预先写入 runsc 的 containerd 模板（config-v3.toml.tmpl，并保留旧版 config.toml.tmpl），平台使用 systrap，网络使用 host。
4. 若本机没有正在运行的 k3s：有可用 systemd 的机器仍用官方安装脚本（--write-kubeconfig-mode 644）；没有可用 systemd 时，以进程方式启动 k3s，参数包括 --write-kubeconfig-mode 644、--flannel-backend=host-gw、--disable=traefik。然后等待节点就绪。
5. 若 containerd 还没有登记 runsc，再重启 k3s（有 systemd 走 systemctl，没有则仍按进程方式拉起），等待节点重新就绪。
6. apply deploy/k8s/runtimeclass-gvisor.yaml，确认 RuntimeClass gvisor 的 handler 是 runsc。
EOF
}

# 缺少命令时以非 0 退出。
need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    say "缺少命令：$1" >&2
    exit 1
  fi
}

# 用 root 身份执行。当前用户不是 root 时走 sudo。
as_root() {
  if [[ "$(id -u)" -eq 0 ]]; then
    "$@"
    return
  fi
  if ! command -v sudo >/dev/null 2>&1; then
    say "需要 root 或 sudo 才能准备 k3s。" >&2
    exit 1
  fi
  sudo "$@"
}

# 只在 Linux 上继续。其它系统直接失败。
require_linux() {
  local os
  os="$(uname -s)"
  if [[ "$os" != "Linux" ]]; then
    say "k3s 与 gVisor 的准备只在 Linux 上执行。当前系统是 ${os}。" >&2
    exit 1
  fi
}

# 让后续 kubectl 找到本机 k3s。已有 KUBECONFIG 时尊重调用方。
export_kubeconfig() {
  if [[ -z "${KUBECONFIG:-}" && -r "${KUBECONFIG_DEFAULT}" ]]; then
    export KUBECONFIG="${KUBECONFIG_DEFAULT}"
  fi
}

# 优先使用 kubectl。没有 kubectl 时使用 k3s 自带的 kubectl。
kctl() {
  if command -v kubectl >/dev/null 2>&1; then
    kubectl "$@"
    return
  fi
  if command -v k3s >/dev/null 2>&1; then
    as_root k3s kubectl "$@"
    return
  fi
  say "缺少 kubectl 或 k3s。" >&2
  exit 1
}

# 本机是否已经有 kubectl 或 k3s。
has_cluster_cli() {
  command -v kubectl >/dev/null 2>&1 || command -v k3s >/dev/null 2>&1
}

# 等到节点接口能回答。超时则非 0 退出。
wait_for_node() {
  local n=0
  while [[ "$n" -lt 60 ]]; do
    if has_cluster_cli && kctl get nodes >/dev/null 2>&1; then
      return 0
    fi
    n=$((n + 1))
    sleep 2
  done
  say "k3s 没有在预期时间内就绪。" >&2
  exit 1
}

# 本机是否有可用的 systemd。没有 /run/systemd/system 时不调用 systemctl。
has_usable_systemd() {
  if [[ ! -d /run/systemd/system ]]; then
    return 1
  fi
  command -v systemctl >/dev/null 2>&1
}

# k3s 进程方式使用的启动参数。有 systemd 的机器不走这条路径。
K3S_PROCESS_ARGS=(--write-kubeconfig-mode 644 --flannel-backend=host-gw --disable=traefik)

# 进程方式的日志。不在这里写出口令或令牌。
K3S_PROCESS_LOG="/var/log/k3s-server.log"

# 查找本机已有的 k3s 可执行文件。可用 JASAGENT_K3S_BIN 指定路径。
resolve_k3s_bin() {
  local c
  for c in "${JASAGENT_K3S_BIN:-}" /usr/local/bin/k3s; do
    if [[ -n "${c}" && -x "${c}" ]]; then
      printf '%s\n' "${c}"
      return 0
    fi
  done
  c="$(command -v k3s 2>/dev/null || true)"
  if [[ -n "${c}" && -x "${c}" ]]; then
    printf '%s\n' "${c}"
    return 0
  fi
  return 1
}

# 本机没有 k3s 时，从官方发布包下载到 /usr/local/bin/k3s。已有则只保证该路径可执行。
ensure_k3s_bin() {
  local src dest="/usr/local/bin/k3s" arch file tmp
  if src="$(resolve_k3s_bin)"; then
    if [[ "${src}" != "${dest}" ]]; then
      as_root ln -sfn "${src}" "${dest}"
    fi
    return 0
  fi
  need curl
  arch="$(uname -m)"
  case "${arch}" in
    x86_64)
      file="k3s"
      ;;
    aarch64)
      file="k3s-arm64"
      ;;
    *)
      say "没有可用 systemd 时，无法为架构 ${arch} 下载 k3s。" >&2
      exit 1
      ;;
  esac
  say "下载官方 k3s 二进制（${arch}）。"
  tmp="$(mktemp)"
  curl -fL --retry 3 --retry-delay 2 -o "${tmp}" "https://github.com/k3s-io/k3s/releases/latest/download/${file}"
  as_root install -m 755 "${tmp}" "${dest}"
  rm -f "${tmp}"
}

# 是否已有 k3s server 进程。
k3s_process_running() {
  pgrep -f '(^|/)k3s( |$)server' >/dev/null 2>&1
}

# 以进程方式启动 k3s。日志重定向必须由 root 打开，不能落到调用用户的家目录。
start_k3s_process() {
  local bin="/usr/local/bin/k3s"
  ensure_k3s_bin
  if k3s_process_running; then
    return 0
  fi
  say "以进程方式启动 k3s（--write-kubeconfig-mode 644 --flannel-backend=host-gw --disable=traefik）。"
  as_root sh -c 'nohup "$1" server --write-kubeconfig-mode 644 --flannel-backend=host-gw --disable=traefik >"$2" 2>&1 &' -- "${bin}" "${K3S_PROCESS_LOG}"
}

# 停掉以进程方式运行的 k3s，再按同样参数拉起。
stop_k3s_process() {
  if ! k3s_process_running; then
    return 0
  fi
  as_root pkill -f '(^|/)k3s( |$)server' >/dev/null 2>&1 || true
  local n=0
  while [[ "$n" -lt 30 ]]; do
    if ! k3s_process_running; then
      return 0
    fi
    n=$((n + 1))
    sleep 1
  done
  as_root pkill -9 -f '(^|/)k3s( |$)server' >/dev/null 2>&1 || true
}

# 返回本机 k3s 的 systemd 单元名。没有可用 systemd 或找不到单元时返回 1。
k3s_service_name() {
  if ! has_usable_systemd; then
    return 1
  fi
  if as_root systemctl cat k3s.service >/dev/null 2>&1; then
    printf '%s\n' "k3s"
    return 0
  fi
  if as_root systemctl cat k3s-agent.service >/dev/null 2>&1; then
    printf '%s\n' "k3s-agent"
    return 0
  fi
  return 1
}

# 重启本机 k3s，让 containerd 重新加载 runsc。有 systemd 走 systemctl，否则按进程方式拉起。
restart_k3s() {
  local unit
  if unit="$(k3s_service_name)"; then
    as_root systemctl restart "${unit}"
    return 0
  fi
  if has_usable_systemd; then
    say "找不到 k3s 服务，无法让 runsc 配置生效。" >&2
    exit 1
  fi
  stop_k3s_process
  start_k3s_process
}

# 本机没有可用 k3s 时再安装或拉起。已经在跑就跳过。
# 有可用 systemd 时仍走官方安装脚本；没有可用 systemd 时走已验证的进程拉起。
install_k3s_if_needed() {
  export_kubeconfig
  if has_cluster_cli && kctl get nodes >/dev/null 2>&1; then
    say "本机 k3s 已在运行。"
    return 0
  fi
  if has_usable_systemd; then
    if k3s_service_name >/dev/null 2>&1; then
      say "启动已安装的 k3s。"
      restart_k3s
      export_kubeconfig
      if kctl get nodes >/dev/null 2>&1; then
        return 0
      fi
    fi
    need curl
    say "安装 k3s。"
    curl -sfL https://get.k3s.io | as_root sh -s - --write-kubeconfig-mode 644
    export_kubeconfig
    wait_for_node
    return 0
  fi
  start_k3s_process
  export_kubeconfig
  wait_for_node
}

# 在当前机器上下载 gVisor 发布包并校验。已安装则跳过。
install_runsc() {
  if as_root test -x "${RUNSC_BIN}" && as_root test -x "${RUNSC_SHIM}" && as_root test -d /usr/local/bin/gvisor-bin; then
    say "runsc 已安装。"
    return 0
  fi
  need curl
  local arch url archive tmp
  arch="$(uname -m)"
  url="${GVISOR_RELEASE_BASE}/${arch}"
  if command -v bzip2 >/dev/null 2>&1; then
    archive="gvisor.tar.bz2"
  else
    archive="gvisor.tar.zstd"
  fi
  say "在当前机器上下载 gVisor runsc（${arch}）。"
  tmp="$(mktemp -d)"
  curl -fL --retry 3 --retry-delay 2 -o "${tmp}/${archive}" "${url}/${archive}"
  curl -fL --retry 3 --retry-delay 2 -o "${tmp}/${archive}.sha512" "${url}/${archive}.sha512"
  (
    cd "${tmp}"
    sha512sum -c "${archive}.sha512"
  )
  if [[ "${archive}" == "gvisor.tar.bz2" ]]; then
    as_root tar -xjf "${tmp}/${archive}" -C /usr/local/bin
  else
    as_root tar --zstd -xf "${tmp}/${archive}" -C /usr/local/bin
  fi
  rm -rf "${tmp}"
  as_root chmod 755 "${RUNSC_BIN}" "${RUNSC_SHIM}"
  if ! as_root test -x "${RUNSC_BIN}" || ! as_root test -x "${RUNSC_SHIM}" || ! as_root test -d /usr/local/bin/gvisor-bin; then
    say "runsc 安装后仍然不可用。" >&2
    exit 1
  fi
  local version
  version="$(as_root "${RUNSC_BIN}" --version || true)"
  if [[ -z "${version}" ]]; then
    say "runsc 没有输出版本。" >&2
    exit 1
  fi
  say "runsc:"
  printf '%s\n' "${version}" | awk 'NR<=2 { print }'
}

# containerd 是否已经登记 runsc。生成文件不存在时返回 1。
containerd_has_runsc() {
  as_root test -f "${CONTAINERD_DIR}/config.toml" && as_root grep -q 'runtimes.runsc' "${CONTAINERD_DIR}/config.toml"
}

# 预先写入 runsc 的 containerd 模板。k3s 尚未启动时也可以先写好，避免首次生成配置后再补。
write_runsc_templates() {
  local tmp
  tmp="$(mktemp -d)"
  cat > "${tmp}/runsc.toml" <<'EOF'
[runsc_config]
  platform = "systrap"
  network = "host"
EOF
  # 新版 k3s 使用 containerd 配置版本 3。base 模板保留原有运行时，再追加 runsc。
  cat > "${tmp}/config-v3.toml.tmpl" <<'EOF'
{{ template "base" . }}

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.runsc]
  runtime_type = "io.containerd.runsc.v1"

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.runsc.options]
  TypeUrl = "io.containerd.runsc.v1.options"
  ConfigPath = "/var/lib/rancher/k3s/agent/etc/containerd/runsc.toml"
EOF
  # 旧版 k3s 只读 config.toml.tmpl。新版在 v3 模板存在时不会用这份。
  cat > "${tmp}/config.toml.tmpl" <<'EOF'
{{ template "base" . }}

[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runsc]
  runtime_type = "io.containerd.runsc.v1"

[plugins."io.containerd.grpc.v1.cri".containerd.runtimes.runsc.options]
  TypeUrl = "io.containerd.runsc.v1.options"
  ConfigPath = "/var/lib/rancher/k3s/agent/etc/containerd/runsc.toml"
EOF
  as_root mkdir -p "${CONTAINERD_DIR}"
  as_root cp "${tmp}/runsc.toml" "${CONTAINERD_DIR}/runsc.toml"
  as_root cp "${tmp}/config-v3.toml.tmpl" "${CONTAINERD_DIR}/config-v3.toml.tmpl"
  as_root cp "${tmp}/config.toml.tmpl" "${CONTAINERD_DIR}/config.toml.tmpl"
  rm -rf "${tmp}"
  say "已预先写入 runsc 的 containerd 模板。"
}

# 把 runsc 登记进 k3s 的 containerd。已经生效则不重启。
configure_containerd() {
  if containerd_has_runsc; then
    say "k3s containerd 已登记 runsc。"
    return 0
  fi
  write_runsc_templates
  restart_k3s
  wait_for_node
  if ! containerd_has_runsc; then
    say "重启后 k3s containerd 仍没有登记 runsc。" >&2
    exit 1
  fi
}

# apply 仓库里的 RuntimeClass，并核对 handler 是 runsc。
apply_runtimeclass() {
  local manifest="${ROOT}/deploy/k8s/runtimeclass-gvisor.yaml"
  if [[ ! -f "${manifest}" ]]; then
    say "找不到 RuntimeClass 清单：${manifest}" >&2
    exit 1
  fi
  kctl apply -f "${manifest}"
  local handler
  handler="$(kctl get runtimeclass gvisor -o custom-columns=HANDLER:.handler --no-headers | awk '{print $1}')"
  if [[ "${handler}" != "runsc" ]]; then
    say "RuntimeClass gvisor 的 handler 不是 runsc。" >&2
    exit 1
  fi
  say "RuntimeClass gvisor 已就绪，handler=runsc。"
}

# 按固定步骤准备集群。任一步失败即以非 0 退出。
# 先装 runsc 并预先写入模板，再拉起 k3s，这样无 systemd 的进程路径在首次启动就能用到 runsc。
main() {
  require_linux
  install_runsc
  write_runsc_templates
  install_k3s_if_needed
  configure_containerd
  apply_runtimeclass
  say "k3s 和 gVisor 已按固定步骤准备好。"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  case "${1:-}" in
    -h|--help)
      usage
      exit 0
      ;;
    --steps)
      print_fixed_steps
      exit 0
      ;;
    "")
      main
      ;;
    *)
      say "不认识的参数：${1}" >&2
      usage >&2
      exit 1
      ;;
  esac
fi
