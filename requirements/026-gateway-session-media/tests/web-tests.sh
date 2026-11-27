#!/usr/bin/env bash
# 共用一次控制台模块测试，给 T-004、T-006 和 T-008 复用。不调用官方合同脚本。
set -euo pipefail

# 本目录路径。
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/lib.sh"

# 这次要实际跑到的 Java 测试类。
ATDD_WEB_CLASSES="JaegerConsoleLinkTest"
# 必须出现在 Surefire 报告里的方法。
ATDD_WEB_METHODS=(
  jaegerEntryIsAnAdminAnchorOutsideTheRouter
)
# 必须出现在前端测试输出里的用例名。
ATDD_WEB_FRONTEND=(
  "只给出网关路径，不带查询串或主机名"
  "只把名单里的类型画成图片、语音或视频"
  "用网关路径展示图片并播放语音、视频"
  "上传走登录 Cookie，地址上没有令牌"
  "页面和样式不写历史入口"
  "没有把 Jaeger 注册成页面路由"
  "admin 能看到指向 Jaeger 的普通链接"
  "普通用户看不到 Jaeger 入口"
)

# 复用已通过的摘要，否则跑控制台模块测试并写入摘要。
atdd_run_web_tests() {
  local summary log
  summary="$(atdd_module_summary web)"
  if [[ -f "${summary}" ]] && grep -q 'PASS web-tests' "${summary}"; then
    local method name
    for method in "${ATDD_WEB_METHODS[@]}"; do
      if ! grep -q "PASS ${method}" "${summary}"; then
        echo "已有摘要缺少 ${method}，重新跑控制台模块测试。" >&2
        rm -f "${summary}"
        break
      fi
    done
    if [[ -f "${summary}" ]]; then
      for name in "${ATDD_WEB_FRONTEND[@]}"; do
        if ! grep -q "PASS frontend:${name}" "${summary}"; then
          echo "已有摘要缺少前端用例 ${name}，重新跑控制台模块测试。" >&2
          rm -f "${summary}"
          break
        fi
      done
    fi
  fi
  if [[ -f "${summary}" ]] && grep -q 'PASS web-tests' "${summary}"; then
    return 0
  fi
  mkdir -p "${ATDD_REQ}/logs"
  log="${ATDD_REQ}/logs/web-test.log"
  cd "${REPO_ROOT}"
  export JAVA_HOME="${ATDD_JAVA_HOME}"
  export PATH="${ATDD_JAVA_HOME}/bin:${ATDD_MAVEN_HOME}/bin:${PATH}"
  atdd_mvn_test jasagent-web "${ATDD_WEB_CLASSES}"
  printf '%s' "${ATDD_CAPTURE_TEXT}" | atdd_redact > "${log}"
  if [[ ! -d "${REPO_ROOT}/jasagent-web/frontend/node_modules" ]]; then
    atdd_run npm ci --prefix "${REPO_ROOT}/jasagent-web/frontend"
    printf '%s' "${ATDD_CAPTURE_TEXT}" | atdd_redact >> "${log}"
    printf '%s' "${ATDD_CAPTURE_TEXT}" | atdd_assert_clean "前端依赖安装输出"
    if [[ "${ATDD_CAPTURE_CODE}" -ne 0 ]]; then
      echo "前端依赖没有装上。" >&2
      tail -n 80 "${log}" >&2
      exit 1
    fi
  fi
  atdd_run npm test --prefix "${REPO_ROOT}/jasagent-web/frontend" -- --reporter=verbose
  printf '%s' "${ATDD_CAPTURE_TEXT}" | atdd_redact >> "${log}"
  printf '%s' "${ATDD_CAPTURE_TEXT}" | atdd_assert_clean "前端测试输出"
  if [[ "${ATDD_CAPTURE_CODE}" -ne 0 ]]; then
    echo "前端测试没有通过。" >&2
    tail -n 80 "${log}" >&2
    exit 1
  fi
  local name
  for name in "${ATDD_WEB_FRONTEND[@]}"; do
    if ! grep -F -q "${name}" "${log}"; then
      echo "前端测试没有跑到：${name}" >&2
      exit 1
    fi
  done
  atdd_write_surefire_summary "${summary}" "jasagent-web" "${ATDD_WEB_METHODS[@]}"
  for name in "${ATDD_WEB_FRONTEND[@]}"; do
    printf 'PASS frontend:%s\n' "${name}" >> "${summary}"
  done
  printf 'PASS web-tests\n' >> "${summary}"
}

atdd_run_web_tests
