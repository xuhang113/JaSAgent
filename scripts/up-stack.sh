#!/usr/bin/env bash
# 一条命令把 agent、网关、agentpass、sidecar、MySQL、Redis 部署进本机 Colima k3s。
# 业务容器使用 gVisor。不提交、不推送、不把口令、令牌或模型密钥写进仓库和日志。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NS=jasagent
if [[ ! -x "${JAVA_HOME:-}/bin/javac" ]] || ! "${JAVA_HOME}/bin/javac" -version 2>&1 | grep -q ' 21\.'; then
  JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi
export JAVA_HOME
export PATH="${JAVA_HOME}/bin:${PATH}"
WORK=""

cleanup() {
  if [[ -n "${WORK}" && -d "${WORK}" ]]; then
    rm -rf "${WORK}"
  fi
}
trap cleanup EXIT

say() {
  printf '%s\n' "$*"
}

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    say "缺少命令：$1" >&2
    exit 1
  fi
}

ensure_runsc() {
  need colima
  need kubectl
  need docker
  if ! colima status >/dev/null 2>&1; then
    say "Colima 没有在运行。" >&2
    exit 1
  fi
  if ! kubectl get nodes >/dev/null 2>&1; then
    say "kubectl 连不上本机集群。" >&2
    exit 1
  fi
  if ! colima ssh -- sudo test -x /usr/local/bin/runsc || ! colima ssh -- sudo test -x /usr/local/bin/containerd-shim-runsc-v1; then
    say "虚拟机里还没有 runsc，先安装。"
    bash "${ROOT}/scripts/sandbox/install-runsc.sh"
  fi
  if ! colima ssh -- sudo grep -q 'runtimes.runsc' /var/lib/rancher/k3s/agent/etc/containerd/config.toml; then
    say "k3s containerd 还没有登记 runsc。"
    local cfg="${HOME}/.jasagent/sandbox/k8s-images"
    mkdir -p "${cfg}"
    chmod 700 "${HOME}/.jasagent" "${HOME}/.jasagent/sandbox" "${cfg}"
    cat > "${cfg}/runsc.toml" <<'EOF'
[runsc_config]
  platform = "systrap"
  network = "host"
EOF
    cat > "${cfg}/config-v3.toml.tmpl" <<'EOF'
{{ template "base" . }}

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.runsc]
  runtime_type = "io.containerd.runsc.v1"

[plugins.'io.containerd.cri.v1.runtime'.containerd.runtimes.runsc.options]
  TypeUrl = "io.containerd.runsc.v1.options"
  ConfigPath = "/var/lib/rancher/k3s/agent/etc/containerd/runsc.toml"
EOF
    colima ssh -- sudo cp "${cfg}/runsc.toml" /var/lib/rancher/k3s/agent/etc/containerd/runsc.toml
    colima ssh -- sudo cp "${cfg}/config-v3.toml.tmpl" /var/lib/rancher/k3s/agent/etc/containerd/config-v3.toml.tmpl
    rm -f "${cfg}/runsc.toml" "${cfg}/config-v3.toml.tmpl"
    colima ssh -- sudo systemctl restart k3s
    local n=0
    while [[ "$n" -lt 60 ]]; do
      if kubectl get nodes >/dev/null 2>&1; then
        break
      fi
      n=$((n + 1))
      sleep 2
    done
    if ! kubectl get nodes >/dev/null 2>&1; then
      say "重启 k3s 后集群没有恢复。" >&2
      exit 1
    fi
  fi
  if ! kubectl get runtimeclass gvisor >/dev/null 2>&1; then
    kubectl apply -f "${ROOT}/deploy/k8s/runtimeclass-gvisor.yaml"
  fi
  say "runsc:"
  colima ssh -- sudo /usr/local/bin/runsc --version | head -n 2
  say "runtimeclass:"
  kubectl get runtimeclass gvisor -o custom-columns=NAME:.metadata.name,HANDLER:.handler
}

package_jars() {
  if [[ ! -x "${JAVA_HOME}/bin/java" ]]; then
    say "需要 Java 21，当前 JAVA_HOME=${JAVA_HOME}" >&2
    exit 1
  fi
  if [[ "${JASAGENT_SKIP_PACKAGE:-}" == "1" ]]; then
    say "跳过 Maven 打包。"
    return 0
  fi
  say "打包 agent、网关、agentpass。"
  (cd "${ROOT}" && mvn -pl jasagent-app,jasagent-gateway,jasagent-agentpass-server -am -DskipTests package)
}

newest_jar() {
  local pattern="$1"
  find "${ROOT}/${pattern%/*}" -maxdepth 1 -type f -name "${pattern##*/}" ! -name '*.original' | sort | tail -n 1
}

prepare_context() {
  local stage="${ROOT}/deploy/docker/staging"
  if [[ ! -d "${stage}/jre/bin" || ! -s "${stage}/ubuntu-base.tar.gz" ]]; then
    say "镜像基底还没准备好，先跑沙箱镜像脚本把 JRE 和 Ubuntu base 放进 staging。"
    bash "${ROOT}/scripts/sandbox/build-images.sh"
  fi
  cat > "${stage}/.dockerignore" <<'EOF'
*
!ubuntu-base.tar.gz
!jre
!jre/**
!jasagent-app.jar
!entrypoint.sh
EOF
}

build_role() {
  local jar="$1"
  local image="$2"
  local role="$3"
  local port="$4"
  local stage="${ROOT}/deploy/docker/staging"
  if [[ ! -s "${jar}" ]]; then
    say "找不到 ${role} 的 jar。" >&2
    exit 1
  fi
  cp "${ROOT}/deploy/k8s/entrypoint.sh" "${stage}/entrypoint.sh"
  chmod 755 "${stage}/entrypoint.sh"
  cp "${jar}" "${stage}/jasagent-app.jar"
  say "构建 ${image}"
  DOCKER_BUILDKIT=0 docker build \
    -f "${ROOT}/deploy/k8s/Dockerfile" \
    --build-arg "JASAGENT_ROLE=${role}" \
    --build-arg "JASAGENT_PORT=${port}" \
    -t "${image}" \
    "${stage}"
}

wait_sidecar() {
  local dir="${ROOT}/jasagent-sidecar"
  local dockerfile="${dir}/src/main/docker/Dockerfile"
  local waited=0
  local limit="${JASAGENT_SIDECAR_WAIT_SECONDS:-2700}"
  while [[ ! -f "${dockerfile}" ]]; do
    if [[ "${waited}" -ge "${limit}" ]]; then
      say "jasagent-sidecar 还没有 src/main/docker/Dockerfile。不会自己写 sidecar。" >&2
      exit 1
    fi
    if [[ $((waited % 30)) -eq 0 ]]; then
      say "等待 ${dockerfile}（已等 ${waited}s）。"
    fi
    sleep 5
    waited=$((waited + 5))
  done
  local previous=""
  local stable=0
  local stable_waited=0
  while [[ "${stable}" -lt 3 && "${stable_waited}" -lt 180 ]]; do
    local signature
    signature="$(find "${dir}" -type f ! -path '*/.git/*' -exec stat -f '%m %z %N' {} + 2>/dev/null | sort | shasum -a 256 | awk '{print $1}')"
    if [[ -n "${previous}" && "${signature}" == "${previous}" ]]; then
      stable=$((stable + 1))
    else
      stable=0
    fi
    previous="${signature}"
    stable_waited=$((stable_waited + 5))
    sleep 5
  done
  say "jasagent-sidecar 已就绪，开始构建镜像。"
}

build_sidecar() {
  local dir="${ROOT}/jasagent-sidecar"
  local jar="${dir}/target/jasagent-sidecar-0.1.0.jar"
  say "打包 jasagent-sidecar。"
  (cd "${ROOT}" && mvn -pl jasagent-sidecar -am -DskipTests package)
  if [[ ! -s "${jar}" ]]; then
    say "找不到 ${jar}。" >&2
    exit 1
  fi
  if docker image inspect eclipse-temurin:21-jre-jammy >/dev/null 2>&1; then
    say "构建 jasagent-sidecar:0.1.0（本机已有 eclipse-temurin）。"
    DOCKER_BUILDKIT=0 docker build \
      -f "${dir}/src/main/docker/Dockerfile" \
      -t jasagent-sidecar:0.1.0 \
      "${dir}"
  else
    say "本机没有 eclipse-temurin，改用已有 jasagent-agent 镜像里的 JRE。不访问 Docker Hub。"
    if ! docker image inspect jasagent-agent:0.1.0 >/dev/null 2>&1; then
      say "没有 jasagent-agent:0.1.0，无法构建 sidecar。" >&2
      exit 1
    fi
    local ctx
    ctx="$(mktemp -d "${HOME}/.jasagent/sandbox/sidecar.XXXXXX")"
    cp "${jar}" "${ctx}/jasagent-sidecar-0.1.0.jar"
    DOCKER_BUILDKIT=0 docker build \
      -f "${ROOT}/deploy/k8s/sidecar-local.Dockerfile" \
      -t jasagent-sidecar:0.1.0 \
      "${ctx}"
    rm -rf "${ctx}"
  fi
  if ! docker image inspect jasagent-sidecar:0.1.0 >/dev/null 2>&1; then
    say "构建没有产出 jasagent-sidecar:0.1.0。" >&2
    exit 1
  fi
}

build_bridge() {
  local ctx
  ctx="$(mktemp -d "${HOME}/.jasagent/sandbox/bridge.XXXXXX")"
  "${JAVA_HOME}/bin/javac" --release 21 -d "${ctx}" "${ROOT}/deploy/k8s/mysql-bridge/TcpForward.java"
  cp "${ROOT}/deploy/k8s/mysql-bridge/Dockerfile" "${ctx}/Dockerfile"
  DOCKER_BUILDKIT=0 docker build -t jasagent-mysql-bridge:0.1.0 "${ctx}"
  rm -rf "${ctx}"
}

import_image() {
  local image="$1"
  local dir="${HOME}/.jasagent/sandbox/k8s-images"
  mkdir -p "${dir}"
  chmod 700 "${dir}"
  local tar
  tar="$(mktemp "${dir}/img.XXXXXX")"
  say "导入 ${image} 到 k3s。"
  docker save -o "${tar}" "${image}"
  colima ssh -- sudo k3s ctr images import "${tar}"
  rm -f "${tar}"
}

k3s_has_image() {
  local name="$1"
  colima ssh -- sudo k3s ctr images ls | grep -F "${name}" >/dev/null
}

write_secrets() {
  mkdir -p "${HOME}/.jasagent/sandbox"
  chmod 700 "${HOME}/.jasagent" "${HOME}/.jasagent/sandbox"
  WORK="$(mktemp -d "${HOME}/.jasagent/sandbox/k8s-secret.XXXXXX")"
  chmod 700 "${WORK}"
  python3 - "${WORK}" <<'PY'
import pathlib
import secrets
import sys

work = pathlib.Path(sys.argv[1])
password_file = pathlib.Path.home() / ".config" / "urag-mysql-root.password"
text = password_file.read_text(encoding="utf-8") if password_file.is_file() else ""
password = text.splitlines()[0].strip() if text.splitlines() else ""
if not password:
    sys.stderr.write("MySQL 口令文件不可用。\n")
    sys.exit(1)
env_file = pathlib.Path("/Users/xuhang/Workspace/imagine-harness/.env")
key = ""
if env_file.is_file():
    for line in env_file.read_text(encoding="utf-8").splitlines():
        if line.startswith("XAI_API_KEY="):
            key = line.split("=", 1)[1].strip()
            if len(key) >= 2 and key[0] == key[-1] and key[0] in "\"'":
                key = key[1:-1]
            break
if not key:
    sys.stderr.write("没有读到 XAI_API_KEY。\n")
    sys.exit(1)
token_file = pathlib.Path.home() / ".jasagent" / "sandbox" / "k8s-internal.token"
token_file.parent.mkdir(parents=True, exist_ok=True)
existing = token_file.read_text(encoding="utf-8").strip() if token_file.is_file() else ""
token = existing if existing else secrets.token_hex(32)
if not existing:
    token_file.write_text(token + "\n", encoding="utf-8")
token_file.chmod(0o600)
(work / "password").write_text(password, encoding="utf-8")
(work / "username").write_text("root", encoding="utf-8")
(work / "jdbc-url").write_text(
    "jdbc:mysql://jasagent-mysql.jasagent.svc.cluster.local:3306/jasagent"
    "?connectTimeout=10000&socketTimeout=15000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED",
    encoding="utf-8",
)
(work / "xai-api-key").write_text(key, encoding="utf-8")
(work / "token").write_text(token, encoding="utf-8")
for name in ("password", "username", "jdbc-url", "xai-api-key", "token"):
    (work / name).chmod(0o600)
PY
  kubectl create secret generic jasagent-mysql -n "${NS}" \
    --from-file=password="${WORK}/password" \
    --from-file=username="${WORK}/username" \
    --from-file=jdbc-url="${WORK}/jdbc-url" \
    --dry-run=client -o yaml > "${WORK}/mysql.yaml"
  kubectl create secret generic jasagent-internal -n "${NS}" \
    --from-file=token="${WORK}/token" \
    --dry-run=client -o yaml > "${WORK}/internal.yaml"
  kubectl create secret generic jasagent-llm -n "${NS}" \
    --from-file=xai-api-key="${WORK}/xai-api-key" \
    --dry-run=client -o yaml > "${WORK}/llm.yaml"
  kubectl apply -f "${WORK}/mysql.yaml" >/dev/null
  kubectl apply -f "${WORK}/internal.yaml" >/dev/null
  kubectl apply -f "${WORK}/llm.yaml" >/dev/null
  say "Secret 已写入集群：jasagent-mysql jasagent-internal jasagent-llm"
  rm -rf "${WORK}"
  WORK=""
}

configure_sidecar_proxy() {
  local meta_dir
  meta_dir="$(mktemp -d "${HOME}/.jasagent/sandbox/proxy.XXXXXX")"
  chmod 700 "${meta_dir}"
  python3 - "${meta_dir}" <<'PY'
import pathlib
import subprocess
import sys

dest = pathlib.Path(sys.argv[1])

def parse_scutil():
    try:
        text = subprocess.check_output(["scutil", "--proxy"], text=True, stderr=subprocess.DEVNULL)
    except (OSError, subprocess.CalledProcessError):
        return {}
    found = {}
    for line in text.splitlines():
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        found[key.strip()] = value.strip()
    return found

def enabled(table, name):
    return table.get(name + "Enable") == "1" and table.get(name + "Proxy") and table.get(name + "Port")

table = parse_scutil()
host = ""
port = ""
if enabled(table, "HTTPS"):
    host = table["HTTPSProxy"]
    port = table["HTTPSPort"]
elif enabled(table, "HTTP"):
    host = table["HTTPProxy"]
    port = table["HTTPPort"]
if not host or not port.isdigit():
    sys.exit(0)
if host in {"127.0.0.1", "localhost", "::1"}:
    host = "host.docker.internal"
flags = " ".join([
    "-Djava.security.egd=file:/dev/./urandom",
    "-Djava.net.preferIPv4Stack=true",
    "-Djava.io.tmpdir=/tmp",
    "-Dhttps.proxyHost=" + host,
    "-Dhttps.proxyPort=" + port,
    "-Dhttp.proxyHost=" + host,
    "-Dhttp.proxyPort=" + port,
    "-Dhttp.nonProxyHosts=localhost|127.0.0.1|*.cluster.local|*.svc|jasagent-agentpass|jasagent-mysql|jasagent-redis|jasagent-gateway|jasagent-sidecar",
])
(dest / "proxy-host").write_text(host, encoding="utf-8")
(dest / "proxy-port").write_text(port, encoding="utf-8")
(dest / "java-tool-options").write_text(flags, encoding="utf-8")
for name in ("proxy-host", "proxy-port", "java-tool-options"):
    (dest / name).chmod(0o600)
PY
  if [[ ! -s "${meta_dir}/proxy-host" || ! -s "${meta_dir}/proxy-port" ]]; then
    say "没有读到宿主机 HTTPS 代理，sidecar 保持直连。"
    rm -rf "${meta_dir}"
    return 0
  fi
  local proxy_host proxy_port proxy_ip
  proxy_host="$(cat "${meta_dir}/proxy-host")"
  proxy_port="$(cat "${meta_dir}/proxy-port")"
  proxy_ip="$(colima ssh -- getent ahostsv4 host.lima.internal 2>/dev/null | awk '{print $1; exit}')"
  if [[ -z "${proxy_ip}" ]]; then
    proxy_ip="$(colima ssh -- awk '/host.lima.internal/ {print $1; exit}' /etc/hosts)"
  fi
  if [[ ! "${proxy_ip}" =~ ^[0-9]+(\.[0-9]+){3}$ || ! "${proxy_port}" =~ ^[0-9]+$ ]]; then
    say "无法把宿主机代理改写成 sidecar 可访问的地址。" >&2
    rm -rf "${meta_dir}"
    return 1
  fi
  kubectl create secret generic jasagent-proxy -n "${NS}" \
    --from-file=java-tool-options="${meta_dir}/java-tool-options" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  cat > "${meta_dir}/policy.yaml" <<EOF
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: jasagent-sidecar-proxy
  namespace: ${NS}
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: jasagent
      app.kubernetes.io/component: sidecar
  policyTypes:
    - Egress
  egress:
    - to:
        - ipBlock:
            cidr: ${proxy_ip}/32
      ports:
        - protocol: TCP
          port: ${proxy_port}
EOF
  kubectl apply -f "${meta_dir}/policy.yaml" >/dev/null
  cat > "${meta_dir}/alias.yaml" <<EOF
spec:
  template:
    spec:
      hostAliases:
        - ip: "${proxy_ip}"
          hostnames:
            - ${proxy_host}
EOF
  kubectl -n "${NS}" patch deployment jasagent-sidecar --type merge --patch-file "${meta_dir}/alias.yaml" >/dev/null
  rm -rf "${meta_dir}"
  say "sidecar 已改用宿主机代理的可达地址。凭据未写入仓库。"
}

ensure_jasagent_database() {
  say "确认集群库 jasagent 存在。"
  kubectl -n "${NS}" exec deploy/jasagent-mysql -c mysql -- bash -c '
    set -eu
    umask 077
    printf "[client]\nuser=root\npassword=%s\n" "$MYSQL_ROOT_PASSWORD" > /tmp/jasagent-client.cnf
    mysql --defaults-extra-file=/tmp/jasagent-client.cnf -h 127.0.0.1 --protocol=TCP -e "CREATE DATABASE IF NOT EXISTS jasagent CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
    rm -f /tmp/jasagent-client.cnf
  '
}

apply_stack() {
  local restart=0
  if kubectl -n "${NS}" get deploy jasagent-gateway >/dev/null 2>&1; then
    restart=1
  fi
  kubectl apply -f "${ROOT}/deploy/k8s/namespace.yaml"
  kubectl apply -f "${ROOT}/deploy/k8s/runtimeclass-gvisor.yaml"
  kubectl apply -f "${ROOT}/deploy/k8s/mysql.yaml"
  kubectl apply -f "${ROOT}/deploy/k8s/redis.yaml"
  kubectl -n "${NS}" rollout status deployment/jasagent-mysql --timeout=300s
  kubectl -n "${NS}" rollout status deployment/jasagent-redis --timeout=180s
  ensure_jasagent_database
  kubectl apply -f "${ROOT}/deploy/k8s/networkpolicy.yaml"
  kubectl apply -f "${ROOT}/deploy/k8s/agentpass.yaml"
  kubectl apply -f "${ROOT}/deploy/k8s/sidecar.yaml"
  configure_sidecar_proxy
  kubectl apply -f "${ROOT}/deploy/k8s/agent.yaml"
  kubectl apply -f "${ROOT}/deploy/k8s/gateway.yaml"
  if [[ "${restart}" -eq 1 ]]; then
    kubectl -n "${NS}" rollout restart \
      statefulset/jasagent-agent \
      deployment/jasagent-gateway \
      deployment/jasagent-agentpass \
      deployment/jasagent-sidecar
  fi
  kubectl -n "${NS}" rollout status deployment/jasagent-agentpass --timeout=420s
  kubectl -n "${NS}" rollout status deployment/jasagent-sidecar --timeout=420s
  kubectl -n "${NS}" rollout status statefulset/jasagent-agent --timeout=420s
  kubectl -n "${NS}" rollout status deployment/jasagent-gateway --timeout=420s
}

dump_fail() {
  kubectl -n "${NS}" get pods -o wide || true
  kubectl -n "${NS}" get events --sort-by=.lastTimestamp | tail -n 50 || true
}

main() {
  need python3
  need openssl
  ensure_runsc
  package_jars
  local agent_jar gateway_jar agentpass_jar
  agent_jar="$(newest_jar "jasagent-app/target/jasagent-app-*.jar")"
  gateway_jar="$(newest_jar "jasagent-gateway/target/jasagent-gateway-*.jar")"
  agentpass_jar="$(newest_jar "jasagent-agentpass-server/target/jasagent-agentpass-server-*-exec.jar")"
  prepare_context
  build_role "${agent_jar}" jasagent-agent:0.1.0 agent 8080
  build_role "${gateway_jar}" jasagent-gateway:0.1.0 gateway 18721
  build_role "${agentpass_jar}" jasagent-agentpass:0.1.0 agentpass 18741
  wait_sidecar
  build_sidecar
  build_bridge
  if ! k3s_has_image "mysql:8.4"; then
    import_image mysql:8.4
  else
    say "k3s 已有 mysql:8.4"
  fi
  if ! k3s_has_image "redis:7"; then
    import_image redis:7
  else
    say "k3s 已有 redis:7"
  fi
  import_image jasagent-agent:0.1.0
  import_image jasagent-gateway:0.1.0
  import_image jasagent-agentpass:0.1.0
  import_image jasagent-sidecar:0.1.0
  import_image jasagent-mysql-bridge:0.1.0
  kubectl apply -f "${ROOT}/deploy/k8s/namespace.yaml"
  write_secrets
  if ! apply_stack; then
    dump_fail
    exit 1
  fi
  if ! bash "${ROOT}/scripts/k8s/probe-egress.sh"; then
    dump_fail
    exit 1
  fi
  say "部署和探测都通过。"
}

if [[ "${1:-}" == "--configure-proxy" ]]; then
  kubectl apply -f "${ROOT}/deploy/k8s/sidecar.yaml" >/dev/null
  configure_sidecar_proxy
  kubectl -n "${NS}" rollout restart deployment/jasagent-sidecar >/dev/null
  kubectl -n "${NS}" rollout status deployment/jasagent-sidecar --timeout=240s
  exit 0
fi
main "$@"
