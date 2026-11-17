#!/usr/bin/env bash
# 在当前这台 Linux 机器上把 JaSAgent 部署进本机 k3s。
# 打包并构建 jasagent-web、网关、agent、agentpass、sidecar 和 rag，再导入本机 k3s。
# apply 含 web、观测组件和 rag，并等待滚动。业务容器使用 gVisor。
# 口令、令牌和模型密钥只从仓库外的文件或环境读取，不写进仓库，也不打印出来。
# 集群未就绪时调用 scripts/k8s/prepare-cluster.sh。固定步骤用该脚本的 --steps 查看。
#
# 用法：bash scripts/up-stack.sh
set -euo pipefail

# 脚本所在仓库。清单、Dockerfile 和集群准备脚本都从这里找。
SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 打包和 jar 所在的仓库根。默认就是脚本所在仓库。
ROOT="${JASAGENT_ROOT:-${SCRIPT_ROOT}}"

# 集群命名空间。清单里的命名空间也是这个名字。
NS=jasagent

# 临时目录，里面是 Secret 文件。退出时删掉，避免口令留在磁盘上。
WORK=""

# 一键打包的 Maven 模块。控制台上游 jasagent-web 和示例检索都在里面。
JASAGENT_PACKAGE_MODULES="${JASAGENT_PACKAGE_MODULES:-jasagent-app,jasagent-gateway,jasagent-agentpass-server,jasagent-web,jasagent-agentpass-rag,jasagent-sidecar}"

# 仓库外的 MySQL 口令文件。可用环境变量改路径。默认在运行用户的配置目录。
JASAGENT_MYSQL_PASSWORD_FILE="${JASAGENT_MYSQL_PASSWORD_FILE:-${HOME}/.config/urag-mysql-root.password}"

# 仓库外的模型密钥文件。其中一行是 XAI_API_KEY=...。也可以只用环境变量 XAI_API_KEY。
JASAGENT_LLM_ENV_FILE="${JASAGENT_LLM_ENV_FILE:-${HOME}/.config/jasagent/llm.env}"

# 内部令牌只放在运行用户的目录，不进仓库。文件不存在时由脚本生成。
JASAGENT_INTERNAL_TOKEN_FILE="${JASAGENT_INTERNAL_TOKEN_FILE:-${HOME}/.jasagent/sandbox/k8s-internal.token}"

# 集群内 MySQL 主机。JDBC 地址按它拼出来，地址里不放口令。
JASAGENT_MYSQL_HOST="${JASAGENT_MYSQL_HOST:-jasagent-mysql.jasagent.svc.cluster.local}"

# Secret 里的数据库账号。口令不放在这个变量里。
JASAGENT_MYSQL_USER="${JASAGENT_MYSQL_USER:-root}"

# 本机已安装的 Java 21 查找目录。只作为 JAVA_HOME 和 PATH 都没有 Java 21 时的补充。
JASAGENT_JAVA_SEARCH_ROOTS="${JASAGENT_JAVA_SEARCH_ROOTS:-/usr/lib/jvm}"

# 先部署命名空间、运行时和中间件。
JASAGENT_EARLY_MANIFESTS=(
  namespace.yaml
  runtimeclass-gvisor.yaml
  mysql.yaml
  redis.yaml
  nacos.yaml
)

# 中间件的滚动等待。MySQL 和 Redis 就绪后才建库。
JASAGENT_EARLY_WAITS=(
  "deployment/jasagent-mysql|300s"
  "deployment/jasagent-redis|180s"
  "deployment/jasagent-nacos|300s"
)

# 业务、网络策略、控制台、观测组件和 rag。网关清单原样 apply，不改副本数和反代地址。
JASAGENT_LATE_MANIFESTS=(
  networkpolicy.yaml
  agentpass.yaml
  agent.yaml
  gateway.yaml
  web.yaml
  observability.yaml
  rag.yaml
  metrics-adapter.yaml
)

# 业务工作负载的滚动等待。web、grafana、otel-collector、prometheus 和 rag 都在里面。
JASAGENT_LATE_WAITS=(
  "deployment/jasagent-agentpass|420s"
  "statefulset/jasagent-agent|420s"
  "deployment/jasagent-gateway|420s"
  "deployment/jasagent-web|420s"
  "deployment/jasagent-otel-collector|420s"
  "deployment/jasagent-prometheus|420s"
  "deployment/jasagent-grafana|420s"
  "deployment/jasagent-rag|420s"
  "deployment/jasagent-metrics-adapter|300s"
)

# 公网入口：Ingress-nginx 与只转到网关的 Ingress。镜像拉取走 each_manifest，apply 在业务就绪之后。
JASAGENT_INGRESS_MANIFESTS=(
  ingress-nginx.yaml
  ingress-gateway.yaml
)

# 镜像标签不变时，这些已经存在的工作负载需要滚动重启才会用上新导入的镜像。
JASAGENT_RESTART_TARGETS=(
  statefulset/jasagent-agent
  deployment/jasagent-gateway
  deployment/jasagent-agentpass
  deployment/jasagent-web
  deployment/jasagent-otel-collector
  deployment/jasagent-prometheus
  deployment/jasagent-grafana
  deployment/jasagent-rag
  deployment/jasagent-nacos
  deployment/jasagent-metrics-adapter
)

# 源码签名连续不变这么多次，才认为 sidecar 可以构建。
JASAGENT_SIDECAR_STABLE_POLLS="${JASAGENT_SIDECAR_STABLE_POLLS:-3}"

# 两次 sidecar 源码签名之间等待的秒数。
JASAGENT_SIDECAR_POLL_SECONDS="${JASAGENT_SIDECAR_POLL_SECONDS:-5}"

# 等待 sidecar 的 Dockerfile 出现的最长时间（秒）。超时以非 0 退出。
JASAGENT_SIDECAR_WAIT_SECONDS="${JASAGENT_SIDECAR_WAIT_SECONDS:-2700}"

# 官方出网探测脚本。默认就是仓库里的 probe-egress.sh，不换成更小的探测。
JASAGENT_PROBE_SCRIPT="${JASAGENT_PROBE_SCRIPT:-${SCRIPT_ROOT}/scripts/k8s/probe-egress.sh}"

# 集群准备脚本。k3s 或 gVisor 还没有时由一键脚本调用。
JASAGENT_PREPARE_SCRIPT="${JASAGENT_PREPARE_SCRIPT:-${SCRIPT_ROOT}/scripts/k8s/prepare-cluster.sh}"

# 这次是否给 sidecar 写过代理。写过就要滚动重启 sidecar。
SIDECAR_PROXY_PATCHED=0

# 打印一行说明。不在这里输出口令、令牌或模型密钥。
say() {
  printf '%s\n' "$*"
}

# 打印用法。不连接集群，也不读取密钥内容。
usage() {
  cat <<'EOF'
用法：bash scripts/up-stack.sh
在当前这台 Linux 机器上打包、构建镜像、导入本机 k3s，并 apply 清单。
集群还没有 k3s 或 gVisor 时，会先执行 scripts/k8s/prepare-cluster.sh。
固定步骤：bash scripts/k8s/prepare-cluster.sh --steps

环境变量：
  JAVA_HOME                       Java 21 的安装目录
  JASAGENT_MYSQL_PASSWORD_FILE    仓库外的 MySQL 口令文件
  JASAGENT_MYSQL_PASSWORD         口令也可以只放在环境里
  JASAGENT_LLM_ENV_FILE           仓库外的模型密钥文件，其中一行是 XAI_API_KEY
  XAI_API_KEY                     模型密钥也可以只放在环境里
  JASAGENT_MYSQL_HOST             集群内 MySQL 主机名
  JASAGENT_JDBC_URL               覆盖 JDBC 地址；不能包含 password=
  JASAGENT_HTTPS_PROXY            可选的 HTTPS 代理
EOF
}

# 缺少命令时以非 0 退出。
need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    say "缺少命令：$1" >&2
    exit 1
  fi
}

# 退出时删掉 Secret 临时目录。
cleanup() {
  if [[ -n "${WORK}" && -d "${WORK}" ]]; then
    rm -rf "${WORK}"
  fi
}

# 用 root 身份执行。当前用户不是 root 时走 sudo。
as_root() {
  if [[ "$(id -u)" -eq 0 ]]; then
    "$@"
    return
  fi
  if ! command -v sudo >/dev/null 2>&1; then
    say "需要 root 或 sudo 才能操作本机 k3s。" >&2
    exit 1
  fi
  sudo "$@"
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

# 本机 k3s 的 kubeconfig 存在且调用方没指定时，用这份配置。
use_k3s_kubeconfig() {
  if [[ -z "${KUBECONFIG:-}" && -r /etc/rancher/k3s/k3s.yaml ]]; then
    export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
  fi
}

# 本机是否能看到节点和 RuntimeClass gvisor。
cluster_ready() {
  if ! command -v kubectl >/dev/null 2>&1 && ! command -v k3s >/dev/null 2>&1; then
    return 1
  fi
  kctl get nodes >/dev/null 2>&1 && kctl get runtimeclass gvisor >/dev/null 2>&1
}

# 确认本机 k3s 和 gVisor 可用。还没有时执行集群准备脚本。
ensure_cluster() {
  use_k3s_kubeconfig
  if cluster_ready; then
    say "本机 k3s 和 RuntimeClass gvisor 已就绪。"
    return 0
  fi
  if [[ ! -f "${JASAGENT_PREPARE_SCRIPT}" ]]; then
    say "找不到集群准备脚本。" >&2
    exit 1
  fi
  bash "${JASAGENT_PREPARE_SCRIPT}"
  use_k3s_kubeconfig
  if ! cluster_ready; then
    say "集群准备结束后，k3s 或 RuntimeClass gvisor 仍不可用。" >&2
    exit 1
  fi
}

# 读取 java -version 的主版本号。解析不了时输出空字符串。
java_major() {
  local bin="$1"
  local raw quoted major
  raw="$("${bin}" -version 2>&1 | head -n 1 || true)"
  quoted=""
  if [[ "${raw}" == *\"*\"* ]]; then
    quoted="${raw#*\"}"
    quoted="${quoted%%\"*}"
  fi
  major="${quoted%%.*}"
  if [[ "${major}" == "1" ]]; then
    major="$(printf '%s' "${quoted}" | cut -d. -f2)"
  fi
  if [[ "${major}" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "${major}"
  fi
}

# 接受一个 Java 21 或更高的可执行文件，并导出 JAVA_HOME。不是 21 则返回 1。
adopt_java() {
  local bin="$1"
  local major resolved home
  [[ -n "${bin}" && -x "${bin}" ]] || return 1
  major="$(java_major "${bin}")"
  if [[ ! "${major}" =~ ^[0-9]+$ ]] || (( major < 21 )); then
    return 1
  fi
  resolved="$(readlink -f "${bin}" 2>/dev/null || printf '%s\n' "${bin}")"
  home="$(cd "$(dirname "${resolved}")/.." && pwd)"
  if [[ ! -x "${home}/bin/java" ]]; then
    return 1
  fi
  export JAVA_HOME="${home}"
  export PATH="${home}/bin:${PATH}"
  return 0
}

# 从 JAVA_HOME 或本机已安装的 Java 21 解析。找不到时以非 0 退出。
resolve_java21() {
  local candidate dir pattern
  if [[ -n "${JAVA_HOME:-}" ]]; then
    if [[ -x "${JAVA_HOME}/bin/java" ]] && adopt_java "${JAVA_HOME}/bin/java"; then
      return 0
    fi
    say "JAVA_HOME 不是 Java 21。" >&2
    exit 1
  fi
  candidate="$(command -v java 2>/dev/null || true)"
  if [[ -n "${candidate}" ]] && adopt_java "${candidate}"; then
    return 0
  fi
  for pattern in 'java-21*' 'java-21-openjdk*' 'temurin-21*'; do
    for dir in "${JASAGENT_JAVA_SEARCH_ROOTS}"/${pattern}; do
      if [[ -x "${dir}/bin/java" ]] && adopt_java "${dir}/bin/java"; then
        return 0
      fi
    done
  done
  say "需要 Java 21。请设置 JAVA_HOME，或在这台机器上安装 Java 21。" >&2
  exit 1
}

# 打包一键部署需要的模块。JASAGENT_SKIP_PACKAGE=1 时跳过，但仍要求 jar 已经存在。
package_jars() {
  if [[ "${JASAGENT_SKIP_PACKAGE:-}" == "1" ]]; then
    say "跳过 Maven 打包。"
    return 0
  fi
  if ! command -v mvn >/dev/null 2>&1; then
    say "需要 Maven 才能打包。找不到 mvn。" >&2
    exit 1
  fi
  say "打包 agent、网关、agentpass、控制台上游、示例检索和 sidecar。"
  (cd "${ROOT}" && mvn -pl "${JASAGENT_PACKAGE_MODULES}" -am -DskipTests package)
}

# 在目录里找最新的一个 jar。没有时输出空字符串。
newest_jar() {
  local pattern="$1"
  local dir="${ROOT}/${pattern%/*}"
  local name="${pattern##*/}"
  if [[ ! -d "${dir}" ]]; then
    return 0
  fi
  find "${dir}" -maxdepth 1 -type f -name "${name}" ! -name '*.original' | sort | tail -n 1
}

# 必要的 jar 不存在或为空时以非 0 退出。
require_jar() {
  local path="$1"
  local label="$2"
  if [[ -z "${path}" || ! -s "${path}" ]]; then
    say "找不到 ${label} 的 jar。" >&2
    exit 1
  fi
}

# 镜像构建上下文目录。可用 JASAGENT_STAGE_DIR 改到别处。
stage_dir() {
  printf '%s\n' "${JASAGENT_STAGE_DIR:-${SCRIPT_ROOT}/deploy/docker/staging}"
}

# 写构建上下文的 dockerignore，只放进基底、JRE、jar 和入口脚本。
write_dockerignore() {
  local stage="$1"
  cat > "${stage}/.dockerignore" <<'EOF'
*
!ubuntu-base.tar.gz
!jre
!jre/**
!jasagent-app.jar
!entrypoint.sh
EOF
}

# 按本机架构选择 Ubuntu base 和 Temurin JRE 的文件名。
image_arch() {
  local arch ubuntu_arch adoptium_arch
  arch="$(docker version --format '{{.Server.Arch}}' 2>/dev/null || true)"
  if [[ -z "${arch}" ]]; then
    arch="$(uname -m)"
  fi
  case "${arch}" in
    arm64|aarch64)
      ubuntu_arch=arm64
      adoptium_arch=aarch64
      ;;
    amd64|x86_64)
      ubuntu_arch=amd64
      adoptium_arch=x64
      ;;
    *)
      say "不支持的机器架构：${arch}" >&2
      exit 1
      ;;
  esac
  printf '%s %s\n' "${ubuntu_arch}" "${adoptium_arch}"
}

# 下载一个文件。第一个地址失败时再试第二个。都失败则以非 0 退出。
download_file() {
  local dest="$1"
  local primary="$2"
  local fallback="$3"
  if ! curl -fL --retry 3 --retry-delay 2 -o "${dest}.partial" "${primary}"; then
    curl -fL --retry 3 --retry-delay 2 -o "${dest}.partial" "${fallback}"
  fi
  mv "${dest}.partial" "${dest}"
}

# 在当前机器上准备 Ubuntu base 和 Temurin 21 JRE。已经有则跳过下载。
prepare_image_base() {
  local stage
  stage="$(stage_dir)"
  mkdir -p "${stage}"
  if [[ -d "${stage}/jre/bin" && -s "${stage}/ubuntu-base.tar.gz" ]]; then
    write_dockerignore "${stage}"
    return 0
  fi
  need curl
  local arch_line ubuntu_arch adoptium_arch base jre_tar
  arch_line="$(image_arch)"
  ubuntu_arch="${arch_line%% *}"
  adoptium_arch="${arch_line##* }"
  base="${stage}/ubuntu-base.tar.gz"
  if [[ ! -s "${base}" ]]; then
    say "在当前机器上下载 Ubuntu base（${ubuntu_arch}）。"
    download_file "${base}" \
      "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-${ubuntu_arch}.tar.gz" \
      "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-${ubuntu_arch}.tar.gz"
  fi
  jre_tar="${stage}/jre.tar.gz"
  if [[ ! -d "${stage}/jre/bin" ]]; then
    if [[ ! -s "${jre_tar}" ]]; then
      say "在当前机器上下载 Temurin 21 JRE（${adoptium_arch}）。"
      local jre_name
      jre_name="OpenJDK21U-jre_${adoptium_arch}_linux_hotspot_21.0.13_9.tar.gz"
      download_file "${jre_tar}" \
        "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jre/${adoptium_arch}/linux/${jre_name}" \
        "https://api.adoptium.net/v3/binary/latest/21/ga/linux/${adoptium_arch}/jre/hotspot/normal/eclipse?project=jdk"
    fi
    rm -rf "${stage}/jre"
    mkdir -p "${stage}/jre"
    tar -xzf "${jre_tar}" -C "${stage}/jre" --strip-components=1
  fi
  if [[ ! -d "${stage}/jre/bin" || ! -s "${base}" ]]; then
    say "镜像基底没有准备好。" >&2
    exit 1
  fi
  write_dockerignore "${stage}"
}

# 用当前机器上的 jar 构建一张镜像。不从别的电脑加载镜像。
build_role() {
  local jar="$1"
  local image="$2"
  local role="$3"
  local port="$4"
  local stage dockerfile entry
  stage="$(stage_dir)"
  dockerfile="${SCRIPT_ROOT}/deploy/k8s/Dockerfile"
  entry="${SCRIPT_ROOT}/deploy/k8s/entrypoint.sh"
  if [[ ! -s "${jar}" ]]; then
    say "找不到 ${role} 的 jar。" >&2
    exit 1
  fi
  if [[ ! -f "${dockerfile}" || ! -f "${entry}" ]]; then
    say "找不到镜像 Dockerfile 或入口脚本。" >&2
    exit 1
  fi
  cp "${entry}" "${stage}/entrypoint.sh"
  chmod 755 "${stage}/entrypoint.sh"
  cp "${jar}" "${stage}/jasagent-app.jar"
  say "构建 ${image}"
  DOCKER_BUILDKIT=0 docker build \
    -f "${dockerfile}" \
    --build-arg "JASAGENT_ROLE=${role}" \
    --build-arg "JASAGENT_PORT=${port}" \
    -t "${image}" \
    "${stage}"
}

# 计算目录里文件的签名，用来判断 sidecar 源码是否还在变化。
source_signature() {
  local dir="$1"
  local stat_args=(-c '%Y %s %n')
  if ! stat -c '%Y' "${dir}" >/dev/null 2>&1; then
    stat_args=(-f '%m %z %N')
  fi
  find "${dir}" -type f ! -path '*/.git/*' -exec stat "${stat_args[@]}" {} + 2>/dev/null \
    | sort \
    | sha256sum \
    | awk '{print $1}'
}

# 等到 sidecar 的 Dockerfile 出现，并且源码签名稳定。超时以非 0 退出。
wait_sidecar() {
  local dir="${ROOT}/jasagent-sidecar"
  local dockerfile="${dir}/src/main/docker/Dockerfile"
  local waited=0
  local limit="${JASAGENT_SIDECAR_WAIT_SECONDS}"
  local polls="${JASAGENT_SIDECAR_STABLE_POLLS}"
  local pause="${JASAGENT_SIDECAR_POLL_SECONDS}"
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
  local previous="" stable=0 spins=0 signature
  while [[ "${stable}" -lt "${polls}" && "${spins}" -lt 40 ]]; do
    signature="$(source_signature "${dir}")"
    if [[ -n "${previous}" && "${signature}" == "${previous}" ]]; then
      stable=$((stable + 1))
    else
      stable=0
    fi
    previous="${signature}"
    spins=$((spins + 1))
    if [[ "${pause}" -gt 0 ]]; then
      sleep "${pause}"
    fi
  done
  say "jasagent-sidecar 已就绪，开始构建镜像。"
}

# 在当前机器上构建 sidecar 镜像。没有 Temurin 基础镜像时改用已经构建的 agent 镜像。
build_sidecar() {
  local image="$1"
  local dir="${ROOT}/jasagent-sidecar"
  local jar="${dir}/target/jasagent-sidecar-0.1.0.jar"
  wait_sidecar
  if [[ ! -s "${jar}" ]]; then
    if ! command -v mvn >/dev/null 2>&1; then
      say "找不到 sidecar 的 jar，也没有 mvn。" >&2
      exit 1
    fi
    say "打包 jasagent-sidecar。"
    (cd "${ROOT}" && mvn -pl jasagent-sidecar -am -DskipTests package)
  fi
  if [[ ! -s "${jar}" ]]; then
    say "找不到 ${jar}。" >&2
    exit 1
  fi
  if docker image inspect eclipse-temurin:21-jre-jammy >/dev/null 2>&1; then
    say "构建 ${image}"
    DOCKER_BUILDKIT=0 docker build \
      -f "${dir}/src/main/docker/Dockerfile" \
      -t "${image}" \
      "${dir}"
  else
    say "本机没有 eclipse-temurin，改用已构建的 jasagent-agent 镜像。不访问镜像仓库。"
    if ! docker image inspect jasagent-agent:0.1.0 >/dev/null 2>&1; then
      say "没有 jasagent-agent:0.1.0，无法构建 sidecar。" >&2
      exit 1
    fi
    local ctx
    mkdir -p "${HOME}/.jasagent/sandbox"
    ctx="$(mktemp -d "${HOME}/.jasagent/sandbox/sidecar.XXXXXX")"
    cp "${jar}" "${ctx}/jasagent-sidecar-0.1.0.jar"
    DOCKER_BUILDKIT=0 docker build \
      -f "${SCRIPT_ROOT}/deploy/k8s/sidecar-local.Dockerfile" \
      -t "${image}" \
      "${ctx}"
    rm -rf "${ctx}"
  fi
  if ! docker image inspect "${image}" >/dev/null 2>&1; then
    say "构建没有产出 ${image}。" >&2
    exit 1
  fi
}

# 清单里的这些镜像由本仓库在当前机器上构建。其余镜像改为拉取。
image_is_local() {
  case "$1" in
    jasagent-agent:*|jasagent-gateway:*|jasagent-agentpass:*|jasagent-web:*|jasagent-rag:*|jasagent-sidecar:*|jasagent-ingress-nginx:*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

# 按清单里的镜像名在当前机器上构建。只接受 image_is_local 认过的名字。
# 构建去掉文件 capabilities 的 Ingress-nginx 控制器镜像，以便在 gVisor 里启动。
build_ingress_nginx() {
  local image="$1"
  local dockerfile="${SCRIPT_ROOT}/deploy/k8s/ingress-nginx.Dockerfile"
  if [[ ! -f "${dockerfile}" ]]; then
    say "找不到 Ingress-nginx Dockerfile：${dockerfile}" >&2
    exit 1
  fi
  say "构建 ${image}（去掉文件 capabilities，供 gVisor 使用）。"
  DOCKER_BUILDKIT=0 docker build -t "${image}" -f "${dockerfile}" "${SCRIPT_ROOT}/deploy/k8s"
}

build_known_image() {
  local image="$1"
  local jar=""
  case "${image}" in
    jasagent-agent:*)
      jar="$(newest_jar "jasagent-app/target/jasagent-app-*.jar")"
      require_jar "${jar}" "agent"
      build_role "${jar}" "${image}" agent 8080
      ;;
    jasagent-gateway:*)
      jar="$(newest_jar "jasagent-gateway/target/jasagent-gateway-*.jar")"
      require_jar "${jar}" "gateway"
      build_role "${jar}" "${image}" gateway 18721
      ;;
    jasagent-agentpass:*)
      jar="$(newest_jar "jasagent-agentpass-server/target/jasagent-agentpass-server-*-exec.jar")"
      require_jar "${jar}" "agentpass"
      build_role "${jar}" "${image}" agentpass 18741
      ;;
    jasagent-web:*)
      jar="$(newest_jar "jasagent-web/target/jasagent-web-*-exec.jar")"
      require_jar "${jar}" "jasagent-web"
      build_role "${jar}" "${image}" web 18722
      ;;
    jasagent-rag:*)
      jar="$(newest_jar "jasagent-agentpass-rag/target/jasagent-agentpass-rag-*-exec.jar")"
      require_jar "${jar}" "jasagent-rag"
      build_role "${jar}" "${image}" rag 18751
      ;;
    jasagent-sidecar:*)
      build_sidecar "${image}"
      ;;
    jasagent-ingress-nginx:*)
      build_ingress_nginx "${image}"
      ;;
    *)
      say "不会在本机构建：${image}" >&2
      exit 1
      ;;
  esac
}

# 读出清单里的 image 行。不修改清单。
manifest_images() {
  local file="$1"
  awk '/^[[:space:]]*image:/ { print $2 }' "${file}"
}

# 按 apply 顺序遍历清单名。
each_manifest() {
  local name
  for name in "${JASAGENT_EARLY_MANIFESTS[@]}" "${JASAGENT_LATE_MANIFESTS[@]}" "${JASAGENT_INGRESS_MANIFESTS[@]}"; do
    printf '%s\n' "${name}"
  done
}

# 清单文件必须存在。缺任何一份都以非 0 退出。
require_manifests() {
  local name path
  while IFS= read -r name; do
    path="${SCRIPT_ROOT}/deploy/k8s/${name}"
    if [[ ! -f "${path}" ]]; then
      say "找不到清单：${path}" >&2
      exit 1
    fi
  done < <(each_manifest)
}

# 构建本次 apply 的清单里、由本仓库产出的镜像。
build_images_from_manifests() {
  local name image seen=" "
  while IFS= read -r name; do
    while IFS= read -r image; do
      [[ -n "${image}" ]] || continue
      case "${seen}" in
        *" ${image} "*) continue ;;
      esac
      seen="${seen}${image} "
      if image_is_local "${image}"; then
        build_known_image "${image}"
      fi
    done < <(manifest_images "${SCRIPT_ROOT}/deploy/k8s/${name}")
  done < <(each_manifest)
}

# 在本机 k3s 的 containerd 里执行 ctr。
k3s_ctr() {
  if [[ "$(id -u)" -eq 0 ]]; then
    k3s ctr "$@"
    return
  fi
  as_root k3s ctr "$@"
}

# 本机 k3s 里是否已经有这个镜像名。
k3s_has_image() {
  local name="$1"
  k3s_ctr images ls | grep -F "${name}" >/dev/null
}

# 把本机 Docker 里的镜像导入本机 k3s。镜像必须先在这台机器上构建或拉取。
import_image() {
  local image="$1"
  local dir="${HOME}/.jasagent/sandbox/k8s-images"
  mkdir -p "${dir}"
  chmod 700 "${HOME}/.jasagent" "${HOME}/.jasagent/sandbox" "${dir}"
  local tar
  tar="$(mktemp "${dir}/img.XXXXXX.tar")"
  say "导入 ${image} 到本机 k3s。"
  docker save -o "${tar}" "${image}"
  k3s_ctr images import "${tar}"
  rm -f "${tar}"
}

# 公共镜像不在本仓库构建。先看本机 k3s，再看本机 Docker，最后在当前机器上拉取并导入。
ensure_remote_image() {
  local image="$1"
  if k3s_has_image "${image}"; then
    say "k3s 已有 ${image}"
    return 0
  fi
  if ! docker image inspect "${image}" >/dev/null 2>&1; then
    say "在当前机器上拉取 ${image}"
    docker pull "${image}"
  fi
  import_image "${image}"
}

# 把本次 apply 的清单所依赖的镜像导入本机 k3s。
import_images_from_manifests() {
  local name image seen=" "
  while IFS= read -r name; do
    while IFS= read -r image; do
      [[ -n "${image}" ]] || continue
      case "${seen}" in
        *" ${image} "*) continue ;;
      esac
      seen="${seen}${image} "
      case "${image}" in
        jasagent-agent:*|jasagent-gateway:*|jasagent-agentpass:*|jasagent-web:*|jasagent-rag:*|jasagent-sidecar:*|jasagent-ingress-nginx:*)
          if ! docker image inspect "${image}" >/dev/null 2>&1; then
            say "清单依赖的镜像还没有在本机构建：${image}" >&2
            exit 1
          fi
          import_image "${image}"
          ;;
        *)
          ensure_remote_image "${image}"
          ;;
      esac
    done < <(manifest_images "${SCRIPT_ROOT}/deploy/k8s/${name}")
  done < <(each_manifest)
}

# 拼出集群内 JDBC 地址。地址里只有主机、库名和超时参数，不放口令。
jdbc_url_for_host() {
  local host="$1"
  printf 'jdbc:mysql://%s:3306/jasagent?connectTimeout=10000&socketTimeout=15000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED\n' "${host}"
}

# 选定要写入 Secret 的 JDBC 地址。调用方传入的地址若带 password= 则拒绝。
resolve_jdbc_url() {
  local url="${JASAGENT_JDBC_URL:-}"
  if [[ -z "${url}" ]]; then
    url="$(jdbc_url_for_host "${JASAGENT_MYSQL_HOST}")"
  fi
  if [[ "${url}" == *password=* ]]; then
    say "JDBC 地址不能包含 password=。" >&2
    exit 1
  fi
  printf '%s\n' "${url}"
}

# 把口令、令牌、模型密钥和 JDBC 地址写到仅属主可读的临时目录。不打印密文。
materialize_secrets() {
  local jdbc
  jdbc="$(resolve_jdbc_url)" || exit 1
  mkdir -p "${HOME}/.jasagent/sandbox"
  chmod 700 "${HOME}/.jasagent" "${HOME}/.jasagent/sandbox"
  # 未指定时用临时目录。调用方可以传入自己的目录，方便核对 JDBC 文件。
  if [[ -n "${JASAGENT_SECRET_DIR:-}" ]]; then
    WORK="${JASAGENT_SECRET_DIR}"
    mkdir -p "${WORK}"
  else
    WORK="$(mktemp -d "${HOME}/.jasagent/sandbox/k8s-secret.XXXXXX")"
  fi
  chmod 700 "${WORK}"
  JASAGENT_RESOLVED_JDBC_URL="${jdbc}" \
  JASAGENT_SECRET_WORK="${WORK}" \
  JASAGENT_MYSQL_PASSWORD_FILE="${JASAGENT_MYSQL_PASSWORD_FILE}" \
  JASAGENT_LLM_ENV_FILE="${JASAGENT_LLM_ENV_FILE}" \
  JASAGENT_INTERNAL_TOKEN_FILE="${JASAGENT_INTERNAL_TOKEN_FILE}" \
  JASAGENT_MYSQL_USER="${JASAGENT_MYSQL_USER}" \
  python3 - <<'PY'
import os
import pathlib
import secrets
import sys

work = pathlib.Path(os.environ["JASAGENT_SECRET_WORK"])
password_file = pathlib.Path(os.environ["JASAGENT_MYSQL_PASSWORD_FILE"])
llm_file = pathlib.Path(os.environ["JASAGENT_LLM_ENV_FILE"])
token_file = pathlib.Path(os.environ["JASAGENT_INTERNAL_TOKEN_FILE"])
jdbc_url = os.environ["JASAGENT_RESOLVED_JDBC_URL"]
user = os.environ["JASAGENT_MYSQL_USER"]

def fail(message):
    sys.stderr.write(message + "\n")
    sys.exit(1)

if "password=" in jdbc_url:
    fail("JDBC 地址不能包含 password=。")

password = os.environ.get("JASAGENT_MYSQL_PASSWORD", "").strip()
if not password:
    if not password_file.is_file():
        fail("MySQL 口令文件不存在。")
    text = password_file.read_text(encoding="utf-8")
    lines = text.splitlines()
    password = lines[0].strip() if lines else ""
if not password:
    fail("MySQL 口令文件是空的。")

key = os.environ.get("XAI_API_KEY", "").strip()
if not key:
    if not llm_file.is_file():
        fail("没有读到模型密钥。请设置 XAI_API_KEY，或提供仓库外的密钥文件。")
    for line in llm_file.read_text(encoding="utf-8").splitlines():
        if line.startswith("XAI_API_KEY="):
            key = line.split("=", 1)[1].strip()
            if len(key) >= 2 and key[0] == key[-1] and key[0] in "\"'":
                key = key[1:-1]
            break
if not key:
    fail("没有读到模型密钥。")

token_file.parent.mkdir(parents=True, exist_ok=True)
existing = token_file.read_text(encoding="utf-8").strip() if token_file.is_file() else ""
token = existing if existing else secrets.token_hex(32)
if not existing:
    token_file.write_text(token + "\n", encoding="utf-8")
    token_file.chmod(0o600)

for name, value in (
    ("password", password),
    ("username", user),
    ("jdbc-url", jdbc_url),
    ("xai-api-key", key),
    ("token", token),
):
    path = work / name
    path.write_text(value, encoding="utf-8")
    path.chmod(0o600)
PY
}

# 把仓库外的密钥写进集群 Secret。标准输出不包含密文。
write_secrets() {
  materialize_secrets
  kctl create secret generic jasagent-mysql -n "${NS}" \
    --from-file=password="${WORK}/password" \
    --from-file=username="${WORK}/username" \
    --from-file=jdbc-url="${WORK}/jdbc-url" \
    --dry-run=client -o yaml > "${WORK}/mysql.yaml"
  kctl create secret generic jasagent-internal -n "${NS}" \
    --from-file=token="${WORK}/token" \
    --dry-run=client -o yaml > "${WORK}/internal.yaml"
  kctl create secret generic jasagent-llm -n "${NS}" \
    --from-file=xai-api-key="${WORK}/xai-api-key" \
    --dry-run=client -o yaml > "${WORK}/llm.yaml"
  kctl apply -f "${WORK}/mysql.yaml" >/dev/null
  kctl apply -f "${WORK}/internal.yaml" >/dev/null
  kctl apply -f "${WORK}/llm.yaml" >/dev/null
  say "Secret 已写入集群：jasagent-mysql jasagent-internal jasagent-llm"
  rm -rf "${WORK}"
  WORK=""
}

# 把 loopback 代理改写成本机节点地址。普通主机名则解析成 IP。
resolve_proxy_ip() {
  local host="$1"
  if [[ "${host}" =~ ^[0-9]+(\.[0-9]+){3}$ && "${host}" != "127.0.0.1" ]]; then
    printf '%s\n' "${host}"
    return 0
  fi
  if [[ "${host}" != "127.0.0.1" && "${host}" != "localhost" && "${host}" != "::1" ]]; then
    local resolved
    resolved="$(getent ahostsv4 "${host}" 2>/dev/null | awk '{print $1; exit}' || true)"
    if [[ "${resolved}" =~ ^[0-9]+(\.[0-9]+){3}$ && "${resolved}" != "127.0.0.1" ]]; then
      printf '%s\n' "${resolved}"
      return 0
    fi
  fi
  local node_ip
  node_ip="$(kctl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || true)"
  if [[ "${node_ip}" =~ ^[0-9]+(\.[0-9]+){3}$ ]]; then
    printf '%s\n' "${node_ip}"
    return 0
  fi
  return 1
}

# 按环境变量配置 sidecar 出站代理。没有配置时保持直连。
configure_sidecar_proxy() {
  SIDECAR_PROXY_PATCHED=0
  local raw="${JASAGENT_HTTPS_PROXY:-${HTTPS_PROXY:-${https_proxy:-}}}"
  if [[ -z "${raw}" ]]; then
    say "没有配置 HTTPS 代理，sidecar 保持直连。"
    return 0
  fi
  mkdir -p "${HOME}/.jasagent/sandbox"
  local meta host port ip effective alias_ip
  meta="$(mktemp -d "${HOME}/.jasagent/sandbox/proxy.XXXXXX")"
  chmod 700 "${meta}"
  if ! JASAGENT_PROXY_URL="${raw}" python3 - "${meta}" <<'PY'
import os
import sys
from pathlib import Path
from urllib.parse import urlparse

dest = Path(sys.argv[1])
raw = os.environ.get("JASAGENT_PROXY_URL", "").strip()
candidate = raw if "://" in raw else "http://" + raw
parsed = urlparse(candidate)
host = parsed.hostname or ""
port = parsed.port
if not host or port is None:
    sys.stderr.write("HTTPS 代理地址无法解析。\n")
    sys.exit(1)
(dest / "proxy-host").write_text(host, encoding="utf-8")
(dest / "proxy-port").write_text(str(port), encoding="utf-8")
(dest / "proxy-host").chmod(0o600)
(dest / "proxy-port").chmod(0o600)
PY
  then
    rm -rf "${meta}"
    say "HTTPS 代理地址无法解析。" >&2
    exit 1
  fi
  host="$(cat "${meta}/proxy-host")"
  port="$(cat "${meta}/proxy-port")"
  if ! ip="$(resolve_proxy_ip "${host}")"; then
    rm -rf "${meta}"
    say "无法解析代理地址。" >&2
    exit 1
  fi
  if [[ "${host}" == "127.0.0.1" || "${host}" == "localhost" || "${host}" == "::1" ]]; then
    effective="${ip}"
    alias_ip=""
  else
    effective="${host}"
    alias_ip="${ip}"
  fi
  {
    printf '%s' "-Djava.security.egd=file:/dev/./urandom"
    printf ' %s' "-Djava.net.preferIPv4Stack=true" "-Djava.io.tmpdir=/tmp"
    printf ' %s' "-Dhttps.proxyHost=${effective}" "-Dhttps.proxyPort=${port}"
    printf ' %s' "-Dhttp.proxyHost=${effective}" "-Dhttp.proxyPort=${port}"
    printf ' %s' "-Dhttp.nonProxyHosts=localhost|127.0.0.1|*.cluster.local|*.svc|jasagent-agentpass|jasagent-mysql|jasagent-redis|jasagent-gateway|jasagent-sidecar|jasagent-web"
  } > "${meta}/java-tool-options"
  chmod 600 "${meta}/java-tool-options"
  kctl create secret generic jasagent-proxy -n "${NS}" \
    --from-file=java-tool-options="${meta}/java-tool-options" \
    --dry-run=client -o yaml | kctl apply -f - >/dev/null
  cat > "${meta}/policy.yaml" <<EOF
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: jasagent-sidecar-proxy
  namespace: ${NS}
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: jasagent
      app.kubernetes.io/component: agent
  policyTypes:
    - Egress
  egress:
    - to:
        - ipBlock:
            cidr: ${ip}/32
      ports:
        - protocol: TCP
          port: ${port}
EOF
  kctl apply -f "${meta}/policy.yaml" >/dev/null
  if [[ -n "${alias_ip}" ]]; then
    cat > "${meta}/alias.yaml" <<EOF
spec:
  template:
    spec:
      hostAliases:
        - ip: "${alias_ip}"
          hostnames:
            - ${effective}
EOF
    kctl -n "${NS}" patch statefulset jasagent-agent --type merge --patch-file "${meta}/alias.yaml" >/dev/null
  fi
  rm -rf "${meta}"
  SIDECAR_PROXY_PATCHED=1
  say "sidecar 已改用当前机器上配置的代理。凭据未写入仓库。"
}


# 在本机编译并安装 agent 进程级出站过滤库，供 agent Pod 的 hostPath 挂载。
# 这台云电脑没有 xt_owner，也不能按 Pod IP / 共享 sandbox cgroup 丢弃出站。
install_egress_filter() {
  local script="${SCRIPT_ROOT}/scripts/k8s/install-egress-filter.sh"
  if [[ -x "${script}" ]]; then
    say "安装 agent 进程级出站过滤库。"
    bash "${script}"
  fi
}

# 确认集群库 jasagent 存在。口令只在数据库容器里使用，不打印。
ensure_jasagent_database() {
  say "确认集群库 jasagent 存在。"
  kctl -n "${NS}" exec deploy/jasagent-mysql -c mysql -- bash -c '
    set -eu
    umask 077
    printf "[client]\nuser=root\npassword=%s\n" "$MYSQL_ROOT_PASSWORD" > /tmp/jasagent-client.cnf
    mysql --defaults-extra-file=/tmp/jasagent-client.cnf -h 127.0.0.1 --protocol=TCP -e "CREATE DATABASE IF NOT EXISTS jasagent CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
    rm -f /tmp/jasagent-client.cnf
  '
}


# 共享 sidecar Deployment 改为每 Pod 容器后，kubectl apply 不会删掉遗留对象。
remove_legacy_shared_sidecar() {
  say "删除遗留的共享 sidecar Deployment 和 Service。"
  kctl -n "${NS}" delete deployment jasagent-sidecar --ignore-not-found=true
  kctl -n "${NS}" delete service jasagent-sidecar --ignore-not-found=true
  kctl -n "${NS}" delete networkpolicy jasagent-sidecar-egress --ignore-not-found=true
}

# apply 一份清单。文件不存在时以非 0 退出。

# 把 Ingress-nginx 这一跳的实际地址写成网关受信任转发名单。
# 只收 Ingress 控制器 Pod IP，或探测到的实际对端；拒绝 0.0.0.0/0、::/0 以及前缀为 0 的网段。
is_unrestricted_cidr() {
  local item="$1"
  case "${item}" in
    0.0.0.0|0.0.0.0/0|::|::/0|0.0.0.0/0,*|*,0.0.0.0/0) return 0 ;;
  esac
  if [[ "${item}" =~ ^[0-9.]+/0$ || "${item}" =~ ^[^,]+/0$ ]]; then
    return 0
  fi
  return 1
}

# 规范化成逗号分隔的 IP/前缀，并拒绝全网信任。
normalize_trusted_cidrs() {
  local raw="$1"
  local item out=""
  IFS=',' read -r -a parts <<< "${raw}"
  for item in "${parts[@]}"; do
    item="$(printf '%s' "${item}" | awk '{$1=$1; print}')"
    [[ -n "${item}" ]] || continue
    if is_unrestricted_cidr "${item}"; then
      say "JASAGENT_GATEWAY_FORWARDED_TRUSTED 不能设成 ${item}。" >&2
      return 1
    fi
    if [[ "${item}" =~ ^[0-9]+(\.[0-9]+){3}$ ]]; then
      item="${item}/32"
    elif [[ "${item}" =~ ^[0-9a-fA-F:]+$ && "${item}" == *:* && "${item}" != */* ]]; then
      item="${item}/128"
    fi
    if [[ -n "${out}" ]]; then
      out="${out},${item}"
    else
      out="${item}"
    fi
  done
  if [[ -z "${out}" ]]; then
    say "没有可用的 Ingress 对端地址，不能写入受信任转发名单。" >&2
    return 1
  fi
  if is_unrestricted_cidr "${out}"; then
    say "JASAGENT_GATEWAY_FORWARDED_TRUSTED 不能设成 ${out}。" >&2
    return 1
  fi
  printf '%s\n' "${out}"
}

# 读出 Ingress-nginx 控制器 Pod IP。
ingress_controller_pod_ips() {
  kctl -n ingress-nginx get pods \
    -l app.kubernetes.io/name=ingress-nginx,app.kubernetes.io/component=controller \
    -o jsonpath='{range .items[*]}{.status.podIP}{"\n"}{end}'
}

# 把受信任转发名单写入 jasagent 命名空间的 ConfigMap，必要时重启网关。
write_forwarded_trusted() {
  local trusted="$1"
  trusted="$(normalize_trusted_cidrs "${trusted}")" || return 1
  local current=""
  current="$(kctl -n "${NS}" get configmap jasagent-gateway-forwarded -o jsonpath='{.data.trusted}' 2>/dev/null || true)"
  kctl -n "${NS}" create configmap jasagent-gateway-forwarded \
    --from-literal=trusted="${trusted}" \
    --dry-run=client -o yaml | kctl apply -f - >/dev/null
  say "已写入 jasagent-gateway-forwarded：Ingress 这一跳 ${trusted}"
  if [[ "${current}" != "${trusted}" ]]; then
    say "受信任转发名单有变化，滚动网关。"
    kctl -n "${NS}" rollout restart deployment/jasagent-gateway
    kctl -n "${NS}" rollout status deployment/jasagent-gateway --timeout=180s
  fi
}

# 业务就绪后安装 Ingress-nginx，再挂上只到网关的 Ingress，并写入受信任转发名单。
apply_ingress_stack() {
  local name
  for name in "${JASAGENT_INGRESS_MANIFESTS[@]}"; do
    apply_manifest "${name}"
  done
  say "等待 ingress-nginx-controller 滚动完成。"
  kctl -n ingress-nginx rollout status deployment/ingress-nginx-controller --timeout=300s
  local ips="" ip
  local n=0
  while [[ "${n}" -lt 30 ]]; do
    ips=""
    while IFS= read -r ip; do
      [[ -n "${ip}" ]] || continue
      if [[ -n "${ips}" ]]; then
        ips="${ips},${ip}"
      else
        ips="${ip}"
      fi
    done < <(ingress_controller_pod_ips)
    if [[ -n "${ips}" ]]; then
      break
    fi
    n=$((n + 1))
    sleep 2
  done
  if [[ -z "${ips}" ]]; then
    say "Ingress-nginx 没有拿到 Pod IP。" >&2
    return 1
  fi
  write_forwarded_trusted "${ips}"
}

apply_manifest() {
  local name="$1"
  local path="${SCRIPT_ROOT}/deploy/k8s/${name}"
  if [[ ! -f "${path}" ]]; then
    say "找不到清单：${path}" >&2
    exit 1
  fi
  say "apply ${name}"
  kctl apply -f "${path}"
}

# 等待一项工作负载。格式是 kind/name|timeout。
wait_rollout_item() {
  local item="$1"
  local target="${item%%|*}"
  local timeout="${item##*|}"
  say "等待 ${target} 滚动完成。"
  kctl -n "${NS}" rollout status "${target}" --timeout="${timeout}"
}

# 按固定顺序部署。中间件就绪并建库后，再部署业务、控制台、观测组件和 rag。
apply_stack() {
  local name item target kind workload
  local -a existed=()
  for target in "${JASAGENT_RESTART_TARGETS[@]}"; do
    kind="${target%%/*}"
    workload="${target##*/}"
    if kctl -n "${NS}" get "${kind}" "${workload}" >/dev/null 2>&1; then
      existed+=("${target}")
    fi
  done
  for name in "${JASAGENT_EARLY_MANIFESTS[@]}"; do
    apply_manifest "${name}"
  done
  for item in "${JASAGENT_EARLY_WAITS[@]}"; do
    wait_rollout_item "${item}"
  done
  ensure_jasagent_database
  for name in "${JASAGENT_LATE_MANIFESTS[@]}"; do
    apply_manifest "${name}"
  done
  configure_sidecar_proxy
  remove_legacy_shared_sidecar
  if [[ "${SIDECAR_PROXY_PATCHED}" == "1" ]]; then
    existed+=("statefulset/jasagent-agent")
  fi
  if [[ "${#existed[@]}" -gt 0 ]]; then
    for target in "${existed[@]}"; do
      say "滚动重启 ${target}"
      kctl -n "${NS}" rollout restart "${target}"
    done
  fi
  for item in "${JASAGENT_LATE_WAITS[@]}"; do
    wait_rollout_item "${item}"
  done
  apply_ingress_stack
}



# 等到 jasagent 命名空间里已有 Pod 都就绪，避免滚动时旧副本还在导致官方探测 pods-ready 失败。
wait_namespace_ready() {
  local deadline=$((SECONDS + 300))
  local bad line ready running
  say "等待命名空间 ${NS} 中的 Pod 全部就绪。"
  while (( SECONDS < deadline )); do
    bad=0
    while IFS= read -r line; do
      [[ -n "${line}" ]] || continue
      ready="$(printf '%s\n' "${line}" | awk '{print $2}')"
      running="$(printf '%s\n' "${line}" | awk '{print $3}')"
      if [[ "${running}" != "Running" || "${ready%%/*}" != "${ready##*/}" ]]; then
        bad=1
      fi
    done < <(kctl -n "${NS}" get pods --no-headers)
    if [[ "${bad}" -eq 0 ]]; then
      kctl -n "${NS}" get pods --no-headers
      return 0
    fi
    sleep 5
  done
  say "等待 Pod 就绪超时。" >&2
  kctl -n "${NS}" get pods -o wide || true
  return 1
}

# 这台云电脑上 k3s 因缺少 IP_SET 会跳过 NetworkPolicy 控制器。
# 若本机存在出站兜底脚本，探测前刷新：放行 cni0，并确认已去掉按 Pod IP 丢弃的 nft 表。
# agent 进程直连外网由 agent 容器的 LD_PRELOAD 过滤库拦住，不是 cgroup BPF。
# 脚本不在仓库里时跳过，不把本机路径写成必须步骤。
refresh_box_egress_helper() {
  local allow="${JASAGENT_ALLOW_CNI:-/home/box/bin/jasagent-allow-cni-forward.sh}"
  local helper="${JASAGENT_ENFORCE_EGRESS:-/home/box/bin/jasagent-enforce-agent-egress.sh}"
  if [[ -x "${allow}" ]]; then
    say "刷新本机 cni0 FORWARD 放行（云电脑兜底）。"
    as_root "${allow}" || true
  fi
  if [[ -x "${helper}" ]]; then
    say "刷新本机 agent 出站兜底（进程级 LD_PRELOAD，不是按 Pod IP 丢弃）。"
    as_root "${helper}" || true
  fi
}

# 部署失败时打印 Pod 和事件，不打印 Secret 内容。
dump_fail() {
  kctl -n "${NS}" get pods -o wide || true
  kctl -n "${NS}" get events --sort-by=.lastTimestamp | tail -n 50 || true
}

# 一条命令完成打包、构建、导入和 apply。探测仍使用官方脚本。
main() {
  trap cleanup EXIT
  need python3
  need openssl
  resolve_java21
  ensure_cluster
  require_manifests
  install_egress_filter
  package_jars
  prepare_image_base
  need docker
  build_images_from_manifests
  import_images_from_manifests
  write_secrets
  # apply_stack 放在子进程里，失败才能中断滚动，同时让父进程打印 Pod 和事件。
  set +e
  (
    set -euo pipefail
    apply_stack
  )
  local apply_code=$?
  set -e
  if [[ "${apply_code}" -ne 0 ]]; then
    dump_fail
    exit 1
  fi
  if [[ ! -f "${JASAGENT_PROBE_SCRIPT}" ]]; then
    say "找不到探测脚本。" >&2
    exit 1
  fi
  wait_namespace_ready
  refresh_box_egress_helper
  if ! bash "${JASAGENT_PROBE_SCRIPT}"; then
    dump_fail
    exit 1
  fi
  say "部署和探测都通过。"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  case "${1:-}" in
    -h|--help)
      usage
      exit 0
      ;;
    --configure-proxy)
      ensure_cluster
      apply_manifest agent.yaml
      configure_sidecar_proxy
      kctl -n "${NS}" rollout restart statefulset/jasagent-agent
      kctl -n "${NS}" rollout status statefulset/jasagent-agent --timeout=240s
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
