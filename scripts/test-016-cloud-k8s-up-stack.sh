#!/usr/bin/env bash
# 016 的合同测试，覆盖规格验收第 1 到第 12 条。
# 不连接真集群，不修改 deploy/k8s，也不修改 sidecar 产品代码。
set -euo pipefail

# 仓库根目录。
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo}"

# 最近一次命令捕获。由 run_capture 写入。
CAPTURE_TEXT=""

# 最近一次命令的退出码。
CAPTURE_CODE=0

# 假的数据库口令，只用于确认输出里没有密文。
db_secret="db-secret-value-016"

# 假的模型密钥，只用于确认输出里没有密文。
xai_secret="xai-secret-value-016"

# 假的内部令牌，只用于确认输出里没有密文。
token_secret="token-secret-value-016"

# 打印一条验收项。
section() {
  printf 'TEST %s\n' "$1"
}

# 打印一条通过。
pass() {
  printf 'PASS %s\n' "$1"
}

# 把密文打成占位符后再输出，避免失败日志本身泄露测试口令。
redact() {
  local text="$1"
  text="${text//${db_secret}/[redacted-db]}"
  text="${text//${xai_secret}/[redacted-key]}"
  text="${text//${token_secret}/[redacted-token]}"
  printf '%s\n' "${text}"
}

# 运行一个函数或命令，把输出和退出码留在 CAPTURE_*。被测函数仍按 set -e 执行。
run_capture() {
  set +e
  CAPTURE_TEXT="$(
    set -euo pipefail
    "$@" 2>&1
  )"
  CAPTURE_CODE=$?
  set -e
}

# 文本里不能出现测试用的口令、密钥或令牌。
assert_clean() {
  local text="$1"
  local label="$2"
  if [[ "${text}" == *"${db_secret}"* || "${text}" == *"${xai_secret}"* || "${text}" == *"${token_secret}"* ]]; then
    echo "${label} 出现了密文。" >&2
    exit 1
  fi
}

# 函数定义的上一行必须是注释。第二个参数为 1 时，顶层常量也要有注释。
assert_comments() {
  local file="$1"
  local check_constants="${2:-0}"
  local prev="" line n=0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    n=$((n + 1))
    if [[ "${line}" =~ ^[a-zA-Z_][a-zA-Z0-9_]*\(\)[[:space:]]*\{$ || ( "${check_constants}" == "1" && "${line}" =~ ^[A-Z][A-Z0-9_]+= ) ]]; then
      if [[ "${prev}" != \#* ]]; then
        echo "${file}:${n} 缺少注释：${line}" >&2
        exit 1
      fi
    fi
    if [[ -n "${line//[[:space:]]/}" ]]; then
      prev="${line}"
    fi
  done < "${file}"
}

# 造一个只回答 -version 的 java。
make_java() {
  local home="$1"
  local version="$2"
  mkdir -p "${home}/bin"
  cat > "${home}/bin/java" <<EOF
#!/bin/bash
if [[ "\${1:-}" == "-version" ]]; then
  echo 'openjdk version "${version}" 2024-07-16' >&2
  exit 0
fi
echo "不应该启动 Java 进程。" >&2
exit 99
EOF
  chmod +x "${home}/bin/java"
}

# 写一个把参数记到日志里的命令桩。
write_stub() {
  local path="$1"
  local name="$2"
  local extra="$3"
  cat > "${path}" <<EOF
#!/bin/bash
printf '${name} %s\n' "\$*" >> "\${JASAGENT_TEST_LOG}"
${extra}
exit 0
EOF
  chmod +x "${path}"
}

home="$(mktemp -d)"
fixture="$(mktemp -d)"
stage="$(mktemp -d)"
stubs="$(mktemp -d)"
fail_stubs="$(mktemp -d)"
secret_dir="$(mktemp -d)"
log="$(mktemp)"
probe="$(mktemp)"
prepare_marker="$(mktemp)"
java21="$(mktemp -d)"
java17="$(mktemp -d)"

# 临时目录在测试结束时删掉。
cleanup_test() {
  rm -rf "${home}" "${fixture}" "${stage}" "${stubs}" "${fail_stubs}" "${secret_dir}" \
    "${log}" "${probe}" "${prepare_marker}" "${java21}" "${java17}"
  if [[ -n "${empty_path:-}" ]]; then
    rm -rf "${empty_path}"
  fi
}
trap cleanup_test EXIT

mkdir -p "${stage}/jre/bin"
printf 'base\n' > "${stage}/ubuntu-base.tar.gz"
mkdir -p \
  "${fixture}/jasagent-app/target" \
  "${fixture}/jasagent-gateway/target" \
  "${fixture}/jasagent-agentpass-server/target" \
  "${fixture}/jasagent-web/target" \
  "${fixture}/jasagent-agentpass-rag/target" \
  "${fixture}/jasagent-sidecar/target" \
  "${fixture}/jasagent-sidecar/src/main/docker"
printf 'jar\n' > "${fixture}/jasagent-app/target/jasagent-app-0.1.0.jar"
printf 'jar\n' > "${fixture}/jasagent-gateway/target/jasagent-gateway-0.1.0.jar"
printf 'jar\n' > "${fixture}/jasagent-agentpass-server/target/jasagent-agentpass-server-0.1.0-exec.jar"
printf 'jar\n' > "${fixture}/jasagent-web/target/jasagent-web-0.1.0-exec.jar"
printf 'jar\n' > "${fixture}/jasagent-agentpass-rag/target/jasagent-agentpass-rag-0.1.0-exec.jar"
printf 'jar\n' > "${fixture}/jasagent-sidecar/target/jasagent-sidecar-0.1.0.jar"
printf 'FROM scratch\n' > "${fixture}/jasagent-sidecar/src/main/docker/Dockerfile"

make_java "${java21}" "21.0.4"
make_java "${java17}" "17.0.9"

cat > "${stubs}/docker" <<'EOF'
#!/bin/bash
printf 'docker %s\n' "$*" >> "${JASAGENT_TEST_LOG}"
if [[ "${1:-}" == "version" ]]; then
  printf '%s\n' "amd64"
fi
if [[ "${1:-}" == "image" && "${2:-}" == "inspect" && "${DOCKER_INSPECT_FAIL:-}" == "1" ]]; then
  exit 1
fi
exit 0
EOF
chmod +x "${stubs}/docker"
write_stub "${stubs}/sudo" sudo ""
write_stub "${stubs}/kubectl" kubectl ""
write_stub "${stubs}/mvn" mvn ""
write_stub "${fail_stubs}/kubectl" kubectl "exit 1"

cat > "${probe}" <<'EOF'
#!/bin/bash
printf 'probe\n' >> "${JASAGENT_TEST_LOG}"
exit 0
EOF
chmod +x "${probe}"

cat > "${prepare_marker}" <<'EOF'
#!/bin/bash
printf 'prepared\n' >> "${JASAGENT_TEST_LOG}"
exit 0
EOF
chmod +x "${prepare_marker}"

mkdir -p "${home}/.config/jasagent" "${home}/.jasagent/sandbox"
printf '%s\n' "${db_secret}" > "${home}/.config/urag-mysql-root.password"
printf 'XAI_API_KEY=%s\n' "${xai_secret}" > "${home}/.config/jasagent/llm.env"
printf '%s\n' "${token_secret}" > "${home}/.jasagent/sandbox/k8s-internal.token"
chmod 600 "${home}/.config/urag-mysql-root.password" "${home}/.config/jasagent/llm.env" \
  "${home}/.jasagent/sandbox/k8s-internal.token"

export HOME="${home}"
export JASAGENT_ROOT="${fixture}"
export JASAGENT_STAGE_DIR="${stage}"
export JASAGENT_TEST_LOG="${log}"
export JASAGENT_MYSQL_PASSWORD_FILE="${home}/.config/urag-mysql-root.password"
export JASAGENT_LLM_ENV_FILE="${home}/.config/jasagent/llm.env"
export JASAGENT_INTERNAL_TOKEN_FILE="${home}/.jasagent/sandbox/k8s-internal.token"
export JASAGENT_PROBE_SCRIPT="${probe}"
export JASAGENT_SIDECAR_POLL_SECONDS=0
export JASAGENT_SIDECAR_STABLE_POLLS=1
export JAVA_HOME="${java21}"
export PATH="${stubs}:${java21}/bin:/usr/bin:/bin"
unset JASAGENT_MYSQL_PASSWORD XAI_API_KEY JASAGENT_JDBC_URL JASAGENT_HTTPS_PROXY HTTPS_PROXY https_proxy DOCKER_INSPECT_FAIL || true

# shellcheck disable=SC1091
source "${repo}/scripts/up-stack.sh"

section "1 打包含 jasagent-web"
if [[ "${JASAGENT_PACKAGE_MODULES}" != *jasagent-web* ]]; then
  echo "打包模块没有 jasagent-web。" >&2
  exit 1
fi
: > "${log}"
unset JASAGENT_SKIP_PACKAGE
run_capture package_jars
if [[ "${CAPTURE_CODE}" -ne 0 ]]; then
  echo "打包步骤失败。" >&2
  redact "${CAPTURE_TEXT}" >&2
  exit 1
fi
if ! grep -q -- '-pl .*jasagent-web' "${log}"; then
  echo "mvn -pl 没有列入 jasagent-web。" >&2
  redact "$(cat "${log}")" >&2
  exit 1
fi
pass "1"

section "6 默认路径不指向个人电脑"
for file in scripts/up-stack.sh scripts/k8s/prepare-cluster.sh; do
  for banned in '/Users/xuhang' '/opt/homebrew' 'colima' 'scutil' 'lima'; do
    if grep -F -q -- "${banned}" "${file}"; then
      echo "${file} 含有不能作为云电脑默认路径的片段。" >&2
      exit 1
    fi
  done
done
if ! grep -F -q '${HOME}/.config/urag-mysql-root.password' scripts/up-stack.sh; then
  echo "MySQL 口令默认路径不是运行用户的配置目录。" >&2
  exit 1
fi
if ! grep -F -q '${HOME}/.config/jasagent/llm.env' scripts/up-stack.sh; then
  echo "模型密钥默认路径不是运行用户的配置目录。" >&2
  exit 1
fi
if ! grep -F -q '${HOME}/.jasagent/sandbox/k8s-internal.token' scripts/up-stack.sh; then
  echo "内部令牌默认路径不在运行用户的目录。" >&2
  exit 1
fi
pass "6"

section "7 密钥缺失时非 0 退出且不打印密文"
saved_password_file="${JASAGENT_MYSQL_PASSWORD_FILE}"
saved_llm_file="${JASAGENT_LLM_ENV_FILE}"
unset JASAGENT_MYSQL_PASSWORD XAI_API_KEY
JASAGENT_LLM_ENV_FILE="${home}/no-such-llm.env"
run_capture materialize_secrets
if [[ "${CAPTURE_CODE}" -eq 0 ]]; then
  echo "缺少模型密钥文件时仍然成功。" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "缺少模型密钥"
if [[ "${CAPTURE_TEXT}" != *"没有读到模型密钥"* ]]; then
  echo "缺少模型密钥时没有说明原因。" >&2
  exit 1
fi
JASAGENT_LLM_ENV_FILE="${saved_llm_file}"
JASAGENT_MYSQL_PASSWORD_FILE="${home}/no-such-password"
run_capture materialize_secrets
if [[ "${CAPTURE_CODE}" -eq 0 ]]; then
  echo "缺少 MySQL 口令文件时仍然成功。" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "缺少 MySQL 口令"
if [[ "${CAPTURE_TEXT}" != *"MySQL 口令文件不存在"* ]]; then
  echo "缺少 MySQL 口令文件时没有说明原因。" >&2
  exit 1
fi
JASAGENT_MYSQL_PASSWORD_FILE="${saved_password_file}"
pass "7"

section "8 JDBC 地址不含 password="
rm -rf "${secret_dir}"
mkdir -p "${secret_dir}"
JASAGENT_SECRET_DIR="${secret_dir}"
unset JASAGENT_JDBC_URL
JASAGENT_MYSQL_HOST="jasagent-mysql.jasagent.svc.cluster.local"
run_capture materialize_secrets
if [[ "${CAPTURE_CODE}" -ne 0 ]]; then
  echo "密钥文件齐全时仍然失败。" >&2
  redact "${CAPTURE_TEXT}" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "写入 JDBC"
jdbc="$(cat "${secret_dir}/jdbc-url")"
expected="jdbc:mysql://jasagent-mysql.jasagent.svc.cluster.local:3306/jasagent?connectTimeout=10000&socketTimeout=15000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED"
if [[ "${jdbc}" != "${expected}" ]]; then
  echo "JDBC 地址不是可配置的集群地址。" >&2
  exit 1
fi
if [[ "${jdbc}" == *password=* || "${jdbc}" == *"${db_secret}"* ]]; then
  echo "JDBC 地址里出现了口令。" >&2
  exit 1
fi
mode="$(stat -c '%a' "${secret_dir}/jdbc-url")"
if [[ "${mode}" != "600" ]]; then
  echo "JDBC 文件权限不是 600。" >&2
  exit 1
fi
JASAGENT_MYSQL_HOST="mysql.internal.example"
rm -rf "${secret_dir}"
mkdir -p "${secret_dir}"
run_capture materialize_secrets
custom="$(cat "${secret_dir}/jdbc-url")"
if [[ "${custom}" != jdbc:mysql://mysql.internal.example:3306/jasagent?* || "${custom}" == *password=* ]]; then
  echo "自定义集群主机没有写进 JDBC 地址。" >&2
  exit 1
fi
JASAGENT_MYSQL_HOST="jasagent-mysql.jasagent.svc.cluster.local"
JASAGENT_JDBC_URL="jdbc:mysql://jasagent-mysql.jasagent.svc.cluster.local:3306/jasagent?password=${db_secret}"
run_capture materialize_secrets
if [[ "${CAPTURE_CODE}" -eq 0 ]]; then
  echo "带 password= 的 JDBC 地址没有被拒绝。" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "拒绝 JDBC"
if [[ "${CAPTURE_TEXT}" != *"password="* ]]; then
  echo "拒绝 JDBC 时没有说明原因。" >&2
  exit 1
fi
unset JASAGENT_JDBC_URL JASAGENT_SECRET_DIR
pass "8"

section "Java 21 缺失时非 0 退出"
saved_java="${JAVA_HOME}"
saved_path="${PATH}"
saved_roots="${JASAGENT_JAVA_SEARCH_ROOTS}"
JAVA_HOME="${java17}"
PATH="${java21}/bin:/usr/bin:/bin"
run_capture resolve_java21
if [[ "${CAPTURE_CODE}" -eq 0 ]]; then
  echo "JAVA_HOME 指向 Java 17 时仍然成功。" >&2
  exit 1
fi
if [[ "${CAPTURE_TEXT}" != *"Java 21"* ]]; then
  echo "缺少 Java 21 时没有说明版本要求。" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "Java 17"
empty_path="$(mktemp -d)"
unset JAVA_HOME
PATH="${empty_path}"
JASAGENT_JAVA_SEARCH_ROOTS="${home}/no-such-jvm"
run_capture resolve_java21
if [[ "${CAPTURE_CODE}" -eq 0 ]]; then
  echo "机器上没有 Java 21 时仍然成功。" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "没有 Java"
JAVA_HOME="${java21}"
PATH="/bin:/usr/bin"
JASAGENT_JAVA_SEARCH_ROOTS="${home}/no-such-jvm"
run_capture resolve_java21
if [[ "${CAPTURE_CODE}" -ne 0 ]]; then
  echo "JAVA_HOME 指向 Java 21 时失败。" >&2
  redact "${CAPTURE_TEXT}" >&2
  exit 1
fi
JAVA_HOME="${saved_java}"
PATH="${saved_path}"
JASAGENT_JAVA_SEARCH_ROOTS="${saved_roots}"
run_capture require_jar "" jasagent-web
if [[ "${CAPTURE_CODE}" -eq 0 || "${CAPTURE_TEXT}" != *"找不到 jasagent-web 的 jar。"* ]]; then
  echo "缺少 jar 时没有以非 0 退出。" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "缺少 jar"
run_capture apply_manifest missing.yaml
if [[ "${CAPTURE_CODE}" -eq 0 || "${CAPTURE_TEXT}" != *"找不到清单"* ]]; then
  echo "缺少清单时没有以非 0 退出。" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "缺少清单"
pass "Java"

section "2 到 5 以及 10 一键路径会构建、导入并 apply"
export JASAGENT_SKIP_PACKAGE=1
: > "${log}"
run_capture main
if [[ "${CAPTURE_CODE}" -ne 0 ]]; then
  echo "一键路径在合同夹具里失败。" >&2
  redact "${CAPTURE_TEXT}" >&2
  redact "$(cat "${log}")" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "一键输出"
assert_clean "$(cat "${log}")" "一键命令"
if [[ "${CAPTURE_TEXT}" != *"部署和探测都通过。"* ]]; then
  echo "一键路径没有跑到探测结束。" >&2
  exit 1
fi
if ! grep -q '^probe$' "${log}"; then
  echo "一键路径没有调用探测脚本。" >&2
  exit 1
fi
for image in jasagent-web:0.1.0 jasagent-rag:0.1.0 jasagent-gateway:0.1.0 jasagent-agent:0.1.0 jasagent-agentpass:0.1.0 jasagent-sidecar:0.1.0; do
  if [[ "${CAPTURE_TEXT}" != *"构建 ${image}"* || "${CAPTURE_TEXT}" != *"导入 ${image} 到本机 k3s。"* ]]; then
    echo "没有在当前机器上构建并导入 ${image}。" >&2
    exit 1
  fi
done
python3 - "${CAPTURE_TEXT}" <<'PY'
import sys
text = sys.argv[1]
for image in ("jasagent-web:0.1.0", "jasagent-rag:0.1.0"):
    build_at = text.find("构建 " + image)
    import_at = text.find("导入 " + image)
    if build_at < 0 or import_at < 0 or build_at > import_at:
        sys.stderr.write("构建没有发生在导入之前：" + image + "\n")
        raise SystemExit(1)
PY
if ! grep -F ' -t jasagent-web:0.1.0' "${log}" | grep -q 'docker build'; then
  echo "docker build 没有构建 jasagent-web:0.1.0。" >&2
  exit 1
fi
if ! grep -F ' -t jasagent-rag:0.1.0' "${log}" | grep -q 'docker build'; then
  echo "docker build 没有构建 jasagent-rag:0.1.0。" >&2
  exit 1
fi
if ! grep -q 'k3s ctr images import' "${log}"; then
  echo "没有把镜像导入本机 k3s。" >&2
  exit 1
fi
rag_image="$(awk '/^[[:space:]]*image:/ { print $2 }' deploy/k8s/rag.yaml)"
if [[ "${rag_image}" != "jasagent-rag:0.1.0" ]]; then
  echo "rag.yaml 的镜像名变了，合同测试需要一起更新。" >&2
  exit 1
fi
if [[ "${CAPTURE_TEXT}" != *"构建 ${rag_image}"* || "${CAPTURE_TEXT}" != *"导入 ${rag_image} 到本机 k3s。"* ]]; then
  echo "apply rag.yaml 时没有构建并导入它依赖的镜像。" >&2
  exit 1
fi
while IFS= read -r image; do
  [[ -n "${image}" ]] || continue
  if [[ "${CAPTURE_TEXT}" != *"导入 ${image} 到本机 k3s。"* && "${CAPTURE_TEXT}" != *"k3s 已有 ${image}"* && "${CAPTURE_TEXT}" != *"在当前机器上拉取 ${image}"* ]]; then
    echo "清单镜像没有在当前机器上准备：${image}" >&2
    exit 1
  fi
done < <(awk '/^[[:space:]]*image:/ { print $2 }' deploy/k8s/web.yaml deploy/k8s/observability.yaml deploy/k8s/rag.yaml deploy/k8s/mysql.yaml deploy/k8s/redis.yaml deploy/k8s/gateway.yaml deploy/k8s/agent.yaml deploy/k8s/agentpass.yaml deploy/k8s/sidecar.yaml)
for manifest in web.yaml observability.yaml rag.yaml gateway.yaml agent.yaml agentpass.yaml sidecar.yaml mysql.yaml redis.yaml networkpolicy.yaml; do
  if [[ "${CAPTURE_TEXT}" != *"apply ${manifest}"* ]]; then
    echo "apply 列表没有 ${manifest}。" >&2
    exit 1
  fi
done
for target in deployment/jasagent-web deployment/jasagent-otel-collector deployment/jasagent-prometheus deployment/jasagent-grafana deployment/jasagent-rag deployment/jasagent-gateway deployment/jasagent-agentpass deployment/jasagent-sidecar statefulset/jasagent-agent deployment/jasagent-mysql deployment/jasagent-redis; do
  if [[ "${CAPTURE_TEXT}" != *"等待 ${target} 滚动完成。"* ]]; then
    echo "没有等待 ${target} 滚动完成。" >&2
    exit 1
  fi
  if ! grep -q "rollout status ${target}" "${log}"; then
    echo "kubectl 没有等待 ${target}。" >&2
    exit 1
  fi
done
if ! grep -q 'replicas: 1' deploy/k8s/gateway.yaml; then
  echo "网关清单不再是单副本。" >&2
  exit 1
fi
if ! grep -q 'http://jasagent-web.jasagent.svc.cluster.local:18722' deploy/k8s/gateway.yaml; then
  echo "网关清单不再反代集群内 jasagent-web。" >&2
  exit 1
fi
if grep -q 'JASAGENT_GATEWAY_WEB_ORIGIN' scripts/up-stack.sh || grep -q 'scale deployment/jasagent-gateway' scripts/up-stack.sh; then
  echo "一键脚本改写了网关反代地址或副本数。" >&2
  exit 1
fi
if grep -Eq 'scp |rsync|docker load' scripts/up-stack.sh scripts/k8s/prepare-cluster.sh "${log}"; then
  echo "镜像步骤把从别的电脑拷贝当成了必经路径。" >&2
  exit 1
fi
pass "2"
pass "3"
pass "4"
pass "5"
pass "10"

section "10 公共镜像在当前机器上拉取"
export DOCKER_INSPECT_FAIL=1
: > "${log}"
run_capture ensure_remote_image mysql:8.4
unset DOCKER_INSPECT_FAIL
if [[ "${CAPTURE_CODE}" -ne 0 ]]; then
  echo "当前机器拉取公共镜像失败。" >&2
  redact "${CAPTURE_TEXT}" >&2
  exit 1
fi
if ! grep -q 'docker pull mysql:8.4' "${log}" || ! grep -q 'k3s ctr images import' "${log}"; then
  echo "公共镜像没有在当前机器上拉取并导入。" >&2
  exit 1
fi
assert_clean "${CAPTURE_TEXT}" "拉取镜像"
assert_clean "$(cat "${log}")" "拉取命令"
pass "10-pull"

section "9 集群准备有固定步骤"
if [[ ! -x scripts/k8s/prepare-cluster.sh ]]; then
  echo "集群准备脚本不可执行。" >&2
  exit 1
fi
steps="$(bash scripts/k8s/prepare-cluster.sh --steps)"
help_text="$(bash scripts/k8s/prepare-cluster.sh --help)"
if [[ "${steps}" != *"1. "* || "${steps}" != *"6. "* ]]; then
  echo "集群准备没有列出固定步骤。" >&2
  exit 1
fi
for word in k3s runsc gVisor sha512 runtimeclass systrap; do
  if [[ "${steps}" != *"${word}"* ]]; then
    echo "固定步骤缺少 ${word}。" >&2
    exit 1
  fi
done
if [[ "${help_text}" != *"prepare-cluster.sh"* ]]; then
  echo "集群准备没有用法说明。" >&2
  exit 1
fi
set +e
bad_text="$(bash scripts/k8s/prepare-cluster.sh --nope 2>&1)"
bad_code=$?
set -e
if [[ "${bad_code}" -eq 0 ]]; then
  echo "不认识的参数仍然成功。" >&2
  exit 1
fi
assert_clean "${bad_text}" "错误参数"
for needle in 'https://get.k3s.io' 'gvisor.tar' 'config-v3.toml.tmpl' 'runtimeclass-gvisor.yaml' 'systemctl restart' 'sha512sum'; do
  if ! grep -F -q -- "${needle}" scripts/k8s/prepare-cluster.sh; then
    echo "集群准备脚本没有实现：${needle}" >&2
    exit 1
  fi
done
main_body="$(awk '/^main\(\)/ { inmain=1 } inmain { print } inmain && /^}$/ { exit }' scripts/k8s/prepare-cluster.sh)"
prev=0
for call in install_k3s_if_needed install_runsc configure_containerd apply_runtimeclass; do
  at="$(printf '%s\n' "${main_body}" | awk -v needle="${call}" '$0 ~ needle { print NR; exit }')"
  if [[ -z "${at}" || "${at}" -le "${prev}" ]]; then
    echo "集群准备 main 没有按固定顺序调用 ${call}。" >&2
    exit 1
  fi
  prev="${at}"
done
if ! grep -F -q 'scripts/k8s/prepare-cluster.sh' scripts/up-stack.sh; then
  echo "一键脚本没有接上集群准备。" >&2
  exit 1
fi
saved_prepare="${JASAGENT_PREPARE_SCRIPT}"
saved_path="${PATH}"
JASAGENT_PREPARE_SCRIPT="${prepare_marker}"
PATH="${fail_stubs}:/usr/bin:/bin"
: > "${log}"
run_capture ensure_cluster
PATH="${saved_path}"
JASAGENT_PREPARE_SCRIPT="${saved_prepare}"
if [[ "${CAPTURE_CODE}" -eq 0 ]]; then
  echo "集群仍不可用时 ensure_cluster 却成功了。" >&2
  exit 1
fi
if ! grep -q '^prepared$' "${log}"; then
  echo "集群不可用时没有调用准备脚本。" >&2
  exit 1
fi
if [[ "${CAPTURE_TEXT}" != *"仍不可用"* ]]; then
  echo "准备失败后没有说明集群仍不可用。" >&2
  exit 1
fi
pass "9"

section "11 不修改 deploy/k8s 和 sidecar 产品代码"
base="e234ec092161b76acbf44f20eb8696ec20456cd1"
if git diff --name-only "${base}" -- deploy/k8s jasagent-sidecar scripts/k8s/probe-egress.sh scripts/start.sh scripts/common.sh scripts/sandbox | grep -q .; then
  echo "实现改动了本轮不能改的路径。" >&2
  git diff --name-only "${base}" -- deploy/k8s jasagent-sidecar scripts/k8s/probe-egress.sh scripts/start.sh scripts/common.sh scripts/sandbox >&2
  exit 1
fi
if git status --porcelain -- deploy/k8s jasagent-sidecar scripts/k8s/probe-egress.sh scripts/start.sh scripts/common.sh scripts/sandbox | grep -q .; then
  echo "工作区里有本轮不能改的路径。" >&2
  git status --porcelain -- deploy/k8s jasagent-sidecar scripts/k8s/probe-egress.sh scripts/start.sh scripts/common.sh scripts/sandbox >&2
  exit 1
fi
pass "11"

section "12 注释、自动测试和可执行测试脚本"
for file in scripts/up-stack.sh scripts/k8s/prepare-cluster.sh; do
  if [[ ! -x "${file}" ]]; then
    echo "${file} 不可执行。" >&2
    exit 1
  fi
  assert_comments "${file}" 1
done
if [[ ! -x scripts/test-016-cloud-k8s-up-stack.sh ]]; then
  echo "合同测试脚本不可执行。" >&2
  exit 1
fi
assert_comments scripts/test-016-cloud-k8s-up-stack.sh 0
if ! grep -q 'probe-egress.sh' scripts/up-stack.sh; then
  echo "一键脚本不再调用官方探测脚本。" >&2
  exit 1
fi
help_out="$(bash scripts/up-stack.sh --help)"
if [[ "${help_out}" != *"prepare-cluster.sh --steps"* ]]; then
  echo "一键脚本的用法没有指向集群准备的固定步骤。" >&2
  exit 1
fi
pass "12"

echo "016 云电脑一键部署合同测试通过。"
