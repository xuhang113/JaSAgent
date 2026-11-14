#!/usr/bin/env bash
# 016 ATDD 共用夹具。只给本目录用例使用，不改产品代码。
set -euo pipefail

ATDD_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ATDD_REQ="$(cd "${ATDD_HERE}/.." && pwd)"
REPO_ROOT="$(cd "${ATDD_HERE}/../../.." && pwd)"

# 假的数据库口令，只用于确认输出里没有密文。
ATDD_DB_SECRET="db-secret-value-016-atdd"

# 假的模型密钥，只用于确认输出里没有密文。
ATDD_XAI_SECRET="xai-secret-value-016-atdd"

# 假的内部令牌，只用于确认输出里没有密文。
ATDD_TOKEN_SECRET="token-secret-value-016-atdd"

ATDD_START_MS=0
ATDD_NAME=""
ATDD_RESULT_TEXT=""
ATDD_LOG=""
ATDD_CAPTURE_TEXT=""
ATDD_CAPTURE_CODE=0

# 当前毫秒时间戳。
atdd_now_ms() {
  python3 -c 'import time; print(int(time.time()*1000))'
}

# 当前本地时间，用于日志。
atdd_now_time() {
  date '+%Y-%m-%dT%H:%M:%S%z'
}

# 把密文打成占位符后再输出。
atdd_redact() {
  local text="$1"
  text="${text//${ATDD_DB_SECRET}/[redacted-db]}"
  text="${text//${ATDD_XAI_SECRET}/[redacted-key]}"
  text="${text//${ATDD_TOKEN_SECRET}/[redacted-token]}"
  printf '%s' "${text}"
}

# 文本里不能出现测试用的口令、密钥或令牌。
atdd_assert_clean() {
  local text="$1"
  local label="$2"
  if [[ "${text}" == *"${ATDD_DB_SECRET}"* || "${text}" == *"${ATDD_XAI_SECRET}"* || "${text}" == *"${ATDD_TOKEN_SECRET}"* ]]; then
    echo "${label} 出现了密文。" >&2
    exit 1
  fi
}

# 写一行检查点到用例日志。
atdd_checkpoint() {
  local name="$1"
  local started="$2"
  local check="$3"
  local detail="$4"
  local now elapsed
  now="$(atdd_now_ms)"
  elapsed=$((now - started))
  printf '%s\t%s\t%s\t%s\t%s\n' "$(atdd_now_time)" "$name" "$check" "$elapsed" "$(atdd_redact "${detail}")"
}

# 准备结果和日志目录，记录开始时间。
atdd_begin() {
  ATDD_NAME="$1"
  ATDD_RESULT_TEXT="$2"
  mkdir -p "${ATDD_REQ}/results" "${ATDD_REQ}/logs"
  ATDD_LOG="${ATDD_REQ}/logs/${ATDD_NAME}.log"
  : > "${ATDD_LOG}"
  ATDD_START_MS="$(atdd_now_ms)"
  cd "${REPO_ROOT}"
}

# 写出结果文件。必须在用例逻辑结束后调用，不能提前 exit。
atdd_finish() {
  local code="$1"
  local end elapsed status
  end="$(atdd_now_ms)"
  elapsed=$((end - ATDD_START_MS))
  if [[ "${code}" -eq 0 ]]; then
    status="通过"
  else
    status="失败"
  fi
  printf '%s\t%s\t%s\t%s\tlogs/%s.log\n' "${ATDD_NAME}" "${status}" "${ATDD_RESULT_TEXT}" "${elapsed}" "${ATDD_NAME}" > "${ATDD_REQ}/results/${ATDD_NAME}.txt"
  return "${code}"
}

# 运行一个函数或命令，把输出和退出码留在 ATDD_CAPTURE_*。
atdd_run() {
  set +e
  ATDD_CAPTURE_TEXT="$(
    set -euo pipefail
    "$@" 2>&1
  )"
  ATDD_CAPTURE_CODE=$?
  set -e
}

# 造一个只回答 -version 的 java。
atdd_make_java() {
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
atdd_write_stub() {
  local path="$1"
  local name="$2"
  local extra="${3:-}"
  cat > "${path}" <<EOF
#!/bin/bash
printf '${name} %s\n' "\$*" >> "\${JASAGENT_TEST_LOG}"
${extra}
exit 0
EOF
  chmod +x "${path}"
}

# 为需要执行一键脚本函数的用例准备隔离环境，并加载 up-stack.sh。
atdd_setup_contract() {
  ATDD_HOME="$(mktemp -d)"
  ATDD_FIXTURE="$(mktemp -d)"
  ATDD_STAGE="$(mktemp -d)"
  ATDD_STUBS="$(mktemp -d)"
  ATDD_FAIL_STUBS="$(mktemp -d)"
  ATDD_SECRET_DIR="$(mktemp -d)"
  ATDD_CMD_LOG="$(mktemp)"
  ATDD_PROBE="$(mktemp)"
  ATDD_PREPARE="$(mktemp)"
  ATDD_JAVA21="$(mktemp -d)"
  ATDD_JAVA17="$(mktemp -d)"

  atdd_cleanup_contract() {
    rm -rf "${ATDD_HOME}" "${ATDD_FIXTURE}" "${ATDD_STAGE}" "${ATDD_STUBS}" \
      "${ATDD_FAIL_STUBS}" "${ATDD_SECRET_DIR}" "${ATDD_CMD_LOG}" "${ATDD_PROBE}" \
      "${ATDD_PREPARE}" "${ATDD_JAVA21}" "${ATDD_JAVA17}"
  }
  trap atdd_cleanup_contract EXIT

  mkdir -p "${ATDD_STAGE}/jre/bin"
  printf 'base\n' > "${ATDD_STAGE}/ubuntu-base.tar.gz"
  mkdir -p \
    "${ATDD_FIXTURE}/jasagent-app/target" \
    "${ATDD_FIXTURE}/jasagent-gateway/target" \
    "${ATDD_FIXTURE}/jasagent-agentpass-server/target" \
    "${ATDD_FIXTURE}/jasagent-web/target" \
    "${ATDD_FIXTURE}/jasagent-agentpass-rag/target" \
    "${ATDD_FIXTURE}/jasagent-sidecar/target" \
    "${ATDD_FIXTURE}/jasagent-sidecar/src/main/docker"
  printf 'jar\n' > "${ATDD_FIXTURE}/jasagent-app/target/jasagent-app-0.1.0.jar"
  printf 'jar\n' > "${ATDD_FIXTURE}/jasagent-gateway/target/jasagent-gateway-0.1.0.jar"
  printf 'jar\n' > "${ATDD_FIXTURE}/jasagent-agentpass-server/target/jasagent-agentpass-server-0.1.0-exec.jar"
  printf 'jar\n' > "${ATDD_FIXTURE}/jasagent-web/target/jasagent-web-0.1.0-exec.jar"
  printf 'jar\n' > "${ATDD_FIXTURE}/jasagent-agentpass-rag/target/jasagent-agentpass-rag-0.1.0-exec.jar"
  printf 'jar\n' > "${ATDD_FIXTURE}/jasagent-sidecar/target/jasagent-sidecar-0.1.0.jar"
  printf 'FROM scratch\n' > "${ATDD_FIXTURE}/jasagent-sidecar/src/main/docker/Dockerfile"

  atdd_make_java "${ATDD_JAVA21}" "21.0.4"
  atdd_make_java "${ATDD_JAVA17}" "17.0.9"

  cat > "${ATDD_STUBS}/docker" <<'STUBDOCKER'
#!/bin/bash
printf 'docker %s\n' "$*" >> "${JASAGENT_TEST_LOG}"
if [[ "${1:-}" == "version" ]]; then
  printf '%s\n' "amd64"
fi
if [[ "${1:-}" == "image" && "${2:-}" == "inspect" && "${DOCKER_INSPECT_FAIL:-}" == "1" ]]; then
  exit 1
fi
exit 0
STUBDOCKER
  chmod +x "${ATDD_STUBS}/docker"
  atdd_write_stub "${ATDD_STUBS}/sudo" sudo ""
  atdd_write_stub "${ATDD_STUBS}/kubectl" kubectl ""
  atdd_write_stub "${ATDD_STUBS}/mvn" mvn ""
  atdd_write_stub "${ATDD_FAIL_STUBS}/kubectl" kubectl "exit 1"

  cat > "${ATDD_PROBE}" <<'STUBPROBE'
#!/bin/bash
printf 'probe\n' >> "${JASAGENT_TEST_LOG}"
exit 0
STUBPROBE
  chmod +x "${ATDD_PROBE}"

  cat > "${ATDD_PREPARE}" <<'STUBPREPARE'
#!/bin/bash
printf 'prepared\n' >> "${JASAGENT_TEST_LOG}"
exit 0
STUBPREPARE
  chmod +x "${ATDD_PREPARE}"

  mkdir -p "${ATDD_HOME}/.config/jasagent" "${ATDD_HOME}/.jasagent/sandbox"
  printf '%s\n' "${ATDD_DB_SECRET}" > "${ATDD_HOME}/.config/urag-mysql-root.password"
  printf 'XAI_API_KEY=%s\n' "${ATDD_XAI_SECRET}" > "${ATDD_HOME}/.config/jasagent/llm.env"
  printf '%s\n' "${ATDD_TOKEN_SECRET}" > "${ATDD_HOME}/.jasagent/sandbox/k8s-internal.token"
  chmod 600 "${ATDD_HOME}/.config/urag-mysql-root.password" "${ATDD_HOME}/.config/jasagent/llm.env" \
    "${ATDD_HOME}/.jasagent/sandbox/k8s-internal.token"

  export HOME="${ATDD_HOME}"
  export JASAGENT_ROOT="${ATDD_FIXTURE}"
  export JASAGENT_STAGE_DIR="${ATDD_STAGE}"
  export JASAGENT_TEST_LOG="${ATDD_CMD_LOG}"
  export JASAGENT_MYSQL_PASSWORD_FILE="${ATDD_HOME}/.config/urag-mysql-root.password"
  export JASAGENT_LLM_ENV_FILE="${ATDD_HOME}/.config/jasagent/llm.env"
  export JASAGENT_INTERNAL_TOKEN_FILE="${ATDD_HOME}/.jasagent/sandbox/k8s-internal.token"
  export JASAGENT_PROBE_SCRIPT="${ATDD_PROBE}"
  export JASAGENT_SIDECAR_POLL_SECONDS=0
  export JASAGENT_SIDECAR_STABLE_POLLS=1
  export JAVA_HOME="${ATDD_JAVA21}"
  export PATH="${ATDD_STUBS}:${ATDD_JAVA21}/bin:/usr/bin:/bin"
  unset JASAGENT_MYSQL_PASSWORD XAI_API_KEY JASAGENT_JDBC_URL JASAGENT_HTTPS_PROXY HTTPS_PROXY https_proxy DOCKER_INSPECT_FAIL JASAGENT_SKIP_PACKAGE JASAGENT_SECRET_DIR || true

  # shellcheck disable=SC1091
  source "${REPO_ROOT}/scripts/up-stack.sh"
}

# 函数定义的上一行必须是注释。第二个参数为 1 时，顶层常量也要有注释。
atdd_assert_comments() {
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
