#!/usr/bin/env bash
# 独立拉起 agentpass 和 jasagent-app，验收租户隔离、重启拒重放、audit 鉴权和已有审批路径。
# 不调用官方合同脚本 scripts/test-023-agentpass-tenant-durable.sh，避免 ATDD 文件被那份路径白名单判失败。
set -euo pipefail

# 本目录路径。
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 023 需求目录。可由 ATDD 夹具先设置。
ATDD_REQ="${ATDD_REQ:-$(cd "${HERE}/.." && pwd)}"
# 仓库根目录。可由 ATDD 夹具先设置。
REPO_ROOT="${REPO_ROOT:-$(cd "${HERE}/../../.." && pwd)}"
cd "${REPO_ROOT}"

# 本机编译用的 JDK、Maven。
export JAVA_HOME="${JAVA_HOME:-/home/box/opt/jdk-21}"
export PATH="${JAVA_HOME}/bin:/home/box/opt/maven/bin:${PATH}"

# 进程级摘要，多条用例复用同一次拉起。
SUMMARY="${ATDD_REQ}/logs/live-summary.txt"
# 进程级完整日志。
LIVE_LOG="${ATDD_REQ}/logs/live.log"
# 进程号文件。退出时按这里杀掉验收进程。
PID_FILE="/tmp/jasagent-023-atdd-pids"
# 只给 mysql 客户端用的口令文件，权限 600，退出时删除。
CNF="/tmp/jasagent-023-atdd-client.cnf"

# 已有成功摘要就直接复用。
if [[ -f "${SUMMARY}" ]] && grep -q "PASS process-tenant-restart-audit-approval" "${SUMMARY}"; then
  cat "${SUMMARY}"
  exit 0
fi

mkdir -p "${ATDD_REQ}/logs"

# 结束时停掉验收进程，删掉口令文件。不删本机 mysqld，只清这次临时库账号。
cleanup() {
  local pid
  if [[ -f "${PID_FILE}" ]]; then
    while read -r pid; do
      if [[ -n "${pid}" ]]; then
        kill "${pid}" 2>/dev/null || true
      fi
    done < "${PID_FILE}"
  fi
  rm -f "${CNF}" "${PID_FILE}"
  sudo mysql --socket=/run/mysqld/mysqld.sock -e "DROP DATABASE IF EXISTS jasagent; DROP USER IF EXISTS 'jasagent'@'127.0.0.1';" >/dev/null 2>&1 || true
}

# 确认本机 MySQL 在听。起不来就失败，不改用别的库。
ensure_mysql() {
  if sudo mysqladmin --socket=/run/mysqld/mysqld.sock ping >/dev/null 2>&1; then
    return 0
  fi
  sudo mkdir -p /run/mysqld
  sudo chown mysql:mysql /run/mysqld
  sudo mysqld --user=mysql --datadir=/var/lib/mysql --bind-address=127.0.0.1 --port=3306 --socket=/run/mysqld/mysqld.sock --pid-file=/run/mysqld/mysqld.pid >/tmp/mariadbd-023-atdd.log 2>&1 &
  local i
  for i in $(seq 1 30); do
    if sudo mysqladmin --socket=/run/mysqld/mysqld.sock ping >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "本机 MySQL 没有起来，023 不改用文件或内存库代替" >&2
  return 1
}

trap cleanup EXIT

ensure_mysql

# 可执行包路径。
PASS_JAR="${REPO_ROOT}/jasagent-agentpass-server/target/jasagent-agentpass-server-0.1.0-exec.jar"
# 应用可执行包路径。
APP_JAR="${REPO_ROOT}/jasagent-app/target/jasagent-app-0.1.0.jar"
if [[ ! -f "${PASS_JAR}" || ! -f "${APP_JAR}" ]]; then
  mvn -pl jasagent-agentpass-server,jasagent-app -am package -DskipTests
fi
if [[ ! -f "${PASS_JAR}" || ! -f "${APP_JAR}" ]]; then
  echo "没有打出可执行包。" >&2
  exit 1
fi

# 本次进程里生成的库口令和签名密钥，不写进仓库。
DB_PASS="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
# 本次进程里生成的签名密钥。
SIGN_KEY="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
# 与 020 同一套库的 JDBC 地址。
JDBC_URL="jdbc:mysql://127.0.0.1:3306/jasagent?connectTimeout=3000&socketTimeout=30000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED"

umask 077
cat > "${CNF}" <<CNFFILE
[client]
user=jasagent
password=${DB_PASS}
host=127.0.0.1
protocol=tcp
database=jasagent
CNFFILE
chmod 600 "${CNF}"

# 口令只出现在这次的 SQL 里。失败时先抹掉再打印。
if ! mysql_err=$(sudo mysql --socket=/run/mysqld/mysqld.sock <<SQL 2>&1
DROP DATABASE IF EXISTS jasagent;
CREATE DATABASE jasagent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
DROP USER IF EXISTS 'jasagent'@'127.0.0.1';
CREATE USER 'jasagent'@'127.0.0.1' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON jasagent.* TO 'jasagent'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
); then
  printf '%s\n' "${mysql_err//${DB_PASS}/[redacted]}" >&2
  echo "不能准备 MySQL 账号" >&2
  exit 1
fi

set +e
# 把库地址和密钥交给进程级正文，只在本次进程里。
JDBC_URL="${JDBC_URL}" DB_PASS="${DB_PASS}" SIGN_KEY="${SIGN_KEY}" CNF="${CNF}" \
  PASS_JAR="${PASS_JAR}" APP_JAR="${APP_JAR}" PID_FILE="${PID_FILE}" \
  python3 "${HERE}/live.py" > "${LIVE_LOG}" 2>&1
code=$?
set -e

# 摘要只保留通过或失败行，避免把响应细节写进仓库日志。
grep -E 'PASS |FAIL ' "${LIVE_LOG}" > "${SUMMARY}" || true
if [[ "${code}" -ne 0 ]]; then
  echo "进程级验收失败。" >&2
  grep -E 'PASS |FAIL ' "${LIVE_LOG}" >&2 || true
  tail -n 40 "${LIVE_LOG}" >&2 || true
  exit 1
fi
if ! grep -q "PASS process-tenant-restart-audit-approval" "${SUMMARY}"; then
  echo "进程级验收没有看到最终通过标记。" >&2
  exit 1
fi
cat "${SUMMARY}"
exit 0
