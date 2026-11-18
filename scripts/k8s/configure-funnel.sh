#!/usr/bin/env bash
# 若本机 tailscale 已经登录到 Hang 的 tailnet，就把 Funnel 指到 Ingress-nginx 的 HTTP NodePort。
# 不发起登录、设备码或浏览器授权。没有登录时以非 0 退出，并说明需要在与部署的 1:1 里完成登录。
# 不打印 auth key、凭证或 Cookie。
set -euo pipefail

NODE_PORT="${JASAGENT_FUNNEL_NODEPORT:-30080}"
LOCAL_URL="${JASAGENT_FUNNEL_LOCAL_URL:-http://127.0.0.1:${NODE_PORT}}"

say() {
  printf '%s\n' "$*"
}

if ! command -v tailscale >/dev/null 2>&1; then
  say "本机没有 tailscale 命令。请先安装官方二进制，再在与部署的 1:1 里登录 Hang 的 Tailscale 账号。" >&2
  exit 2
fi

# BackendState 为 Running 才视为已登录。NeedLogin / NoState 则停止，不调用 up 或 login。
state="$(tailscale status --json 2>/dev/null | python3 -c 'import json,sys; print((json.load(sys.stdin).get("BackendState") or "unknown"))' 2>/dev/null || true)"
if [[ "${state}" != "Running" ]]; then
  say "本机 Tailscale 未登录（BackendState=${state:-unknown}）。创建 Funnel 需要 Hang 的账号，不能在群里做交互登录。" >&2
  say "请在与部署的 1:1 里完成：安装官方 tailscale 后执行 tailscale up，再运行 bash scripts/k8s/configure-funnel.sh。" >&2
  exit 2
fi

# 开启 serve/funnel，把稳定的 *.ts.net HTTPS 指到本机 Ingress-nginx NodePort。
# Funnel 终止 TLS，以 HTTP 转到 Ingress；Ingress 再补 X-Forwarded-Proto: https。
# 若 tailnet 尚未在控制台开启 Serve / Funnel / HTTPS，`tailscale serve --bg` 会打印
# login.tailscale.com/f/serve 链接并一直等待。这里限时执行，遇到该提示即以状态码 2 退出，
# 不发起 login / up，也不在本机替操作员点击控制台。
run_tailscale_bg() {
  local label="$1"
  shift
  local log
  log="$(mktemp)"
  set +e
  timeout 20 "$@" >"${log}" 2>&1
  local rc=$?
  set -e
  if grep -q 'Serve is not enabled' "${log}"; then
    cat "${log}" >&2
    say "本机 Tailscale 已登录，但该 tailnet 尚未在控制台开启 Serve / Funnel / HTTPS。" >&2
    say "请由 Hang 用已登录的 Tailscale 账号打开上面打印的 login.tailscale.com/f/serve 链接并开启，然后再执行 bash scripts/k8s/configure-funnel.sh。不要在本机执行 tailscale up 或 login。" >&2
    rm -f "${log}"
    exit 2
  fi
  if [[ "${rc}" -ne 0 ]]; then
    cat "${log}" >&2
    say "${label} 未能完成（exit=${rc}）。" >&2
    rm -f "${log}"
    exit 1
  fi
  if [[ -s "${log}" ]]; then
    cat "${log}"
  fi
  rm -f "${log}"
}

run_tailscale_bg "tailscale serve" tailscale serve --bg "${LOCAL_URL}"
run_tailscale_bg "tailscale funnel" tailscale funnel --bg 443

host="$(tailscale status --json | python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("Self") or {}).get("DNSName") or "")' | sed 's/\.$//')"
if [[ -z "${host}" || "${host}" != *.ts.net ]]; then
  say "已登录，但没有读到 *.ts.net 主机名。" >&2
  exit 1
fi
say "FUNNEL READY https://${host}"
say "path: https://${host} -> Funnel -> Ingress-nginx NodePort ${NODE_PORT} -> jasagent-gateway:18721"
