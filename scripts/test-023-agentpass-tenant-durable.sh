#!/usr/bin/env bash
# 023 的合同测试，覆盖规格验收第 1 到第 9 条。
# 身份、票据、流水和待批都走本机 MySQL 的 jasagent 库。库起不来就失败，不用文件或内存库代替。
# 不修改 jasagent-sidecar、deploy/k8s、probe-egress、018/021/022 脚本、tdd.md、results/ 和 deploy.md。
# 第 10 到第 12 条是部署阶段，本脚本不覆盖。
set -euo pipefail

# 仓库根目录。
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo}"

# 本机 Maven 若不在 PATH 上，使用已解压的 3.9.9。不改仓库里的构建文件。
if [[ -x /tmp/apache-maven-3.9.9/bin/mvn ]]; then
  export PATH="/tmp/apache-maven-3.9.9/bin:${PATH}"
fi

# 允许出现在本轮差异里的路径。其它模块出现即失败。
allowed_re='^(jasagent-agentpass/|jasagent-agentpass-server/|jasagent-app/|scripts/test-023-agentpass-tenant-durable\.sh$|changelog/2026-11-20-agentpass-tenant-durable\.md$)'
# 进程号文件。退出时按这里杀掉验收进程。
pid_file="/tmp/jasagent-023-pids"
# 只给 mysql 客户端用的口令文件，权限 600，退出时删除。
cnf="/tmp/jasagent-023-client.cnf"

# 打印一条验收项。
section() {
  printf 'TEST %s\n' "$1"
}

# 打印一条通过。
pass() {
  printf 'PASS %s\n' "$1"
}

# 打印原因并退出。不接收密钥、令牌或证明文。
fail() {
  printf 'FAIL %s\n' "$1" >&2
  exit 1
}

# 函数或全大写常量的上一行必须是注释。
assert_comments() {
  local file="$1"
  local prev="" line n=0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    n=$((n + 1))
    if [[ "${line}" =~ ^[a-zA-Z_][a-zA-Z0-9_]*\(\)[[:space:]]*\{$ || "${line}" =~ ^[A-Z][A-Z0-9_]+= ]]; then
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

# 结束时停掉验收进程，删掉口令文件，并清掉这次临时库账号。
cleanup() {
  local pid
  if [[ -f "${pid_file}" ]]; then
    while read -r pid; do
      if [[ -n "${pid}" ]]; then
        kill "${pid}" 2>/dev/null || true
      fi
    done < "${pid_file}"
  fi
  rm -f "${cnf}" "${pid_file}"
  sudo mysql --socket=/run/mysqld/mysqld.sock -e "DROP DATABASE IF EXISTS jasagent; DROP USER IF EXISTS 'jasagent'@'127.0.0.1';" >/dev/null 2>&1 || true
}

# 列出相对基线的已改和未跟踪文件。
changed_files() {
  git diff --name-only "${base}"
  git ls-files --others --exclude-standard
}

# 确认本机 MySQL 在听。起不来就失败，不改用别的库。
ensure_mysql() {
  if sudo mysqladmin --socket=/run/mysqld/mysqld.sock ping >/dev/null 2>&1; then
    return 0
  fi
  sudo mkdir -p /run/mysqld
  sudo chown mysql:mysql /run/mysqld
  sudo mysqld --user=mysql --datadir=/var/lib/mysql --bind-address=127.0.0.1 --port=3306 --socket=/run/mysqld/mysqld.sock --pid-file=/run/mysqld/mysqld.pid >/tmp/mariadbd.log 2>&1 &
  local i
  for i in $(seq 1 30); do
    if sudo mysqladmin --socket=/run/mysqld/mysqld.sock ping >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  fail "本机 MySQL 没有起来，023 不改用文件或内存库代替"
}

trap cleanup EXIT

section "注释、差异范围和现有审批路径"
assert_comments "${repo}/scripts/test-023-agentpass-tenant-durable.sh"
base="$(git merge-base HEAD origin/master)"
mapfile -t files < <(changed_files | sed '/^$/d' | sort -u)
if [[ "${#files[@]}" -eq 0 ]]; then
  fail "相对基线没有差异"
fi
for file in "${files[@]}"; do
  case "${file}" in
    jasagent-sidecar/*|deploy/k8s/*|results/*|scripts/k8s/probe-egress.sh|scripts/sandbox/start-agentpass.sh|tdd.md|deploy.md|*/tdd.md|*/deploy.md)
      fail "不允许改动 ${file}"
      ;;
    scripts/test-01*.sh|scripts/test-020*.sh|scripts/test-021*.sh|scripts/test-022*.sh)
      fail "不允许改动 ${file}"
      ;;
  esac
  if [[ ! "${file}" =~ ${allowed_re} ]]; then
    fail "路径不在本轮允许范围内：${file}"
  fi
done
if ! git diff --quiet "${base}" -- jasagent-sidecar deploy/k8s scripts/k8s/probe-egress.sh scripts/sandbox/start-agentpass.sh scripts/test-018-sidecar-egress.sh scripts/test-021-sidecar-egress-threads.sh scripts/test-022-runtime-full-async.sh; then
  fail "sidecar、部署清单或 018/021/022 脚本有差异"
fi
if git diff "${base}" | grep -E '^\+' | grep -Eq 'BEGIN PRIVATE KEY|JASAGENT_AGENTPASS_SIGNING_KEY=[A-Za-z0-9]|password=[A-Za-z0-9]{8,}'; then
  fail "差异里有像密钥的新增行"
fi
grep -q "/passes/" jasagent-web/frontend/src/views/ConsoleView.vue
grep -q "EXECUTION_PASS" jasagent-gateway/src/main/java/dev/jasagent/gateway/route/GatewayPaths.java
grep -q "127.0.0.1" jasagent-agentpass-server/src/main/java/dev/jasagent/agentpass/server/AgentPassServerApplication.java
if git diff "${base}" | grep -E '^\+' | grep -Eq '/v1/approvals|/api/agentpass'; then
  fail "出现了第二套审批接口"
fi
pass "差异只在允许的模块，审批仍走已有通行证路径"

section "模块测试"
env -u JASAGENT_JDBC_URL -u JASAGENT_JDBC_PASSWORD -u JASAGENT_JDBC_USER -u JASAGENT_AGENTPASS_SIGNING_KEY \
  mvn -pl jasagent-agentpass,jasagent-agentpass-server -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=DurablePassGateTest,PolicyPassGateTest,PassGateTest,OneTimePassHttpTest,ExpiredPassTest,SigningKeyRequiredTest,DurableTicketRestartTest
env -u JASAGENT_JDBC_URL -u JASAGENT_JDBC_PASSWORD -u JASAGENT_JDBC_USER -u JASAGENT_AGENTPASS_SIGNING_KEY \
  mvn -pl jasagent-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DurablePassResumeTest
env -u JASAGENT_JDBC_URL -u JASAGENT_JDBC_PASSWORD -u JASAGENT_JDBC_USER -u JASAGENT_AGENTPASS_SIGNING_KEY \
  JASAGENT_CHECKPOINT=sqlite \
  mvn -pl jasagent-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RestartResumeTest,ConsolePageTest
pass "通行证、恢复和原有控制台测试通过"

section "打包并准备 MySQL"
mvn -pl jasagent-agentpass-server,jasagent-app -am package -DskipTests
pass_jar="${repo}/jasagent-agentpass-server/target/jasagent-agentpass-server-0.1.0-exec.jar"
app_jar="${repo}/jasagent-app/target/jasagent-app-0.1.0.jar"
[[ -f "${pass_jar}" && -f "${app_jar}" ]] || fail "没有打出可执行包"
ensure_mysql
db_pass="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
sign_key="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
jdbc_url="jdbc:mysql://127.0.0.1:3306/jasagent?connectTimeout=3000&socketTimeout=30000&allowPublicKeyRetrieval=true&useSSL=false&sslMode=DISABLED"
umask 077
cat > "${cnf}" <<EOF
[client]
user=jasagent
password=${db_pass}
host=127.0.0.1
protocol=tcp
database=jasagent
EOF
chmod 600 "${cnf}"
# 口令只出现在这次的 SQL 里。失败时先抹掉再打印。
if ! mysql_err=$(sudo mysql --socket=/run/mysqld/mysqld.sock <<SQL 2>&1
DROP DATABASE IF EXISTS jasagent;
CREATE DATABASE jasagent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
DROP USER IF EXISTS 'jasagent'@'127.0.0.1';
CREATE USER 'jasagent'@'127.0.0.1' IDENTIFIED BY '${db_pass}';
GRANT ALL PRIVILEGES ON jasagent.* TO 'jasagent'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
); then
  printf '%s\n' "${mysql_err//${db_pass}/[redacted]}" >&2
  fail "不能准备 MySQL 账号"
fi
pass "MySQL 账号只在本次进程里使用"

section "进程级租户、重启、审计和审批"
# 下面的环境变量只传给验收进程，不写进仓库。
JDBC_URL="${jdbc_url}" DB_PASS="${db_pass}" SIGN_KEY="${sign_key}" CNF="${cnf}" \
  PASS_JAR="${pass_jar}" APP_JAR="${app_jar}" PID_FILE="${pid_file}" \
  python3 - <<'PY'
import hashlib, json, os, pathlib, shutil, socket, subprocess, tempfile, threading, time, urllib.error, urllib.request

pid_file = os.environ["PID_FILE"]
secrets = [os.environ["DB_PASS"], os.environ["SIGN_KEY"]]
procs = []
tickets = []

def redact(text):
    value = "" if text is None else str(text)
    for secret in secrets:
        if secret:
            value = value.replace(secret, "[redacted]")
    return value

def check(name, cond, detail=""):
    if not cond:
        raise SystemExit("FAIL " + name + " " + redact(detail))
    print("PASS " + name, flush=True)

def remember(pid):
    with open(pid_file, "a") as handle:
        handle.write(str(pid) + "\n")

def sql(statement):
    proc = subprocess.run(
        ["mysql", "--defaults-extra-file=" + os.environ["CNF"], "--batch", "--skip-column-names"],
        input=statement, text=True, capture_output=True)
    if proc.returncode != 0:
        raise SystemExit("FAIL mysql " + redact(proc.stderr))
    return proc.stdout.strip()

def start_java(args, env, log_path):
    log = open(log_path, "w")
    proc = subprocess.Popen(args, env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    procs.append((proc, log, log_path))
    remember(proc.pid)
    return proc

def stop(proc):
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=10)

def assert_clean(path):
    text = pathlib.Path(path).read_text(errors="replace")
    for secret in secrets:
        if secret and secret in text:
            raise SystemExit("FAIL secret appeared in " + path)

def tail_safe(path):
    if not os.path.exists(path):
        return ""
    text = redact(pathlib.Path(path).read_text(errors="replace"))
    lines = [line for line in text.splitlines() if "Exception" in line or "ERROR" in line or "Caused by" in line]
    return "\n".join(lines[-30:])

def wait_http(proc, url, log_path, seconds=60):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if proc.poll() is not None:
            raise SystemExit("FAIL process exited " + tail_safe(log_path))
        try:
            with urllib.request.urlopen(url, timeout=2) as resp:
                if resp.status == 200:
                    return
        except Exception:
            time.sleep(0.4)
    raise SystemExit("FAIL health timeout " + tail_safe(log_path))

def assert_loopback(port):
    found = False
    for name, v6 in (("/proc/net/tcp", False), ("/proc/net/tcp6", True)):
        with open(name) as handle:
            next(handle, None)
            for line in handle:
                local = line.split()[1]
                ip, hexport = local.split(":")
                if int(hexport, 16) != port:
                    continue
                found = True
                ok = ip == "0100007F" if not v6 else ip in {
                    "00000000000000000000000000000001",
                    "0000000000000000FFFF00000100007F",
                }
                check("listen-127.0.0.1", ok, name + " " + ip)
    check("listen-present", found, str(port))

def call(method, url, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        if value is not None:
            req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read().decode()
            code = resp.status
    except urllib.error.HTTPError as ex:
        raw = ex.read().decode()
        code = ex.code
    try:
        doc = json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        doc = {"raw": raw[:180]}
    if isinstance(doc, dict) and doc.get("ticket"):
        tickets.append(doc["ticket"])
        secrets.append(doc["ticket"])
    return code, doc, raw

def public_view(doc):
    if isinstance(doc, dict):
        return {key: value for key, value in doc.items() if key != "ticket"}
    return doc

def issue(base, tenant, user, session, execution, tool, token):
    return call("POST", base + "/v1/tickets", {
        "tenantId": tenant,
        "userId": user,
        "sessionId": session,
        "executionId": execution,
        "target": "http://127.0.0.1:18751",
        "toolName": tool,
    }, {"Authorization": "Bearer " + token} if token else None)

def proof(base, path, ticket, tenant, user, session, execution, tool):
    body = {
        "ticket": ticket,
        "userId": user,
        "sessionId": session,
        "executionId": execution,
        "target": "http://127.0.0.1:18751",
        "toolName": tool,
    }
    if tenant is not None:
        body["tenantId"] = tenant
    return call("POST", base + path, body)

def sha256(text):
    return hashlib.sha256(text.encode()).hexdigest()

def seed():
    alice = secrets_token()
    bob = secrets_token()
    secrets.extend([alice, bob])
    sql(
        "INSERT INTO jasagent_account (login, password_hash, role, tenant_id, created_at) VALUES "
        "('alice','not-used','user','tenant-a','2026-11-20T00:00:00Z'),"
        "('bob','not-used','user','tenant-b','2026-11-20T00:00:00Z')"
    )
    sql(
        "INSERT INTO jasagent_login_session (session_id, token_hash, login, workspace, created_at) VALUES "
        f"('alice-session','{sha256(alice)}','alice','','2026-11-20T00:00:00Z'),"
        f"('bob-session','{sha256(bob)}','bob','','2026-11-20T00:00:00Z')"
    )
    sql(
        "INSERT INTO jasagent_agentpass_policy (tenant_id, kind, subject, decision) VALUES "
        "('tenant-a','tool','rag.search','ALLOW'),"
        "('tenant-b','tool','rag.search','ALLOW')"
    )
    return alice, bob

def secrets_token():
    return os.urandom(24).hex()

home = tempfile.mkdtemp(prefix="jasagent-023-home-")
log_dir = tempfile.mkdtemp(prefix="jasagent-023-log-")
policy = pathlib.Path(home, "alice", "jasagent", "auth", "policies.yaml")
policy.parent.mkdir(parents=True)
policy.write_text("rules:\n  - tool: rag.search\n    decision: DENY\n  - tool: rag.hold\n    decision: ALLOW\n")
session_file = pathlib.Path(home, "alice", "jasagent", "auth", "sessions", "file-only")
session_file.parent.mkdir(parents=True)
session_file.write_text("login: alice\n")

def pass_env():
    return {
        "PATH": os.environ["PATH"],
        "HOME": os.environ.get("HOME", "/tmp"),
        "LANG": "C.UTF-8",
        "JASAGENT_AGENTPASS_SIGNING_KEY": os.environ["SIGN_KEY"],
        "JASAGENT_JDBC_URL": os.environ["JDBC_URL"],
        "JASAGENT_JDBC_USER": "jasagent",
        "JASAGENT_JDBC_PASSWORD": os.environ["DB_PASS"],
    }

def boot(port, log_name):
    log_path = str(pathlib.Path(log_dir, log_name))
    proc = start_java(
        ["java", "-jar", os.environ["PASS_JAR"],
         "--server.address=127.0.0.1", "--server.port=" + str(port),
         "--jasagent.home=" + home],
        pass_env(), log_path)
    wait_http(proc, f"http://127.0.0.1:{port}/health", log_path)
    assert_loopback(port)
    assert_clean(log_path)
    return proc, log_path

port = socket.socket()
port.bind(("127.0.0.1", 0))
pass_port = port.getsockname()[1]
port.close()
server, server_log = boot(pass_port, "pass-1.log")
base = f"http://127.0.0.1:{pass_port}"
alice, bob = seed()

code, doc, raw = issue(base, "tenant-a", "alice", "file-only", "exec-file", "rag.search", alice)
check("file-session-rejected", code == 403 and doc.get("reason") == "not-logged-in", public_view(doc))
code, issued, raw = issue(base, "tenant-a", "alice", "alice-session", "exec-mysql", "rag.search", alice)
check("mysql-session-ignores-yaml", code == 201 and issued.get("issued") is True and issued.get("tenantId") == "tenant-a", public_view(issued))
check("issued-has-no-token", alice not in raw and bob not in raw and os.environ["SIGN_KEY"] not in raw, "response")
mysql_ticket = issued["ticket"]

code, held, raw = issue(base, "tenant-a", "alice", "alice-session", "exec-hold", "rag.hold", alice)
check("policy-miss-403", code == 403 and held.get("reason") == "approval-required" and "ticket" not in held, public_view(held))
pending = sql("SELECT decision, tenant_id, user_id FROM jasagent_agentpass_pending WHERE execution_id='exec-hold'")
check("policy-miss-row", pending == "PENDING\ttenant-a\talice", pending)

code, fresh, raw = issue(base, "tenant-a", "alice", "alice-session", "exec-fresh", "rag.search", alice)
check("fresh-issued", code == 201 and fresh.get("issued") is True, public_view(fresh))
fresh_ticket = fresh["ticket"]

code, bob_issued, raw = issue(base, "tenant-b", "bob", "bob-session", "exec-bob", "rag.search", bob)
check("bob-issued", code == 201 and bob_issued.get("tenantId") == "tenant-b", public_view(bob_issued))
bob_ticket = bob_issued["ticket"]

code, denied, raw = issue(base, "tenant-b", "alice", "alice-session", "exec-cross-tenant", "rag.search", alice)
check("cannot-claim-other-tenant", code == 403 and denied.get("reason") == "session-mismatch", public_view(denied))
code, denied, raw = issue(base, "tenant-a", "alice", "bob-session", "exec-cross-session", "rag.search", alice)
check("cannot-use-other-session", code == 403 and denied.get("reason") == "session-mismatch", public_view(denied))

code, verified, raw = proof(base, "/v1/tickets/verify", bob_ticket, "tenant-a", "bob", "bob-session", "exec-bob", "rag.search")
check("verify-other-tenant-rejected", code == 200 and verified.get("accepted") is False and verified.get("reason") == "binding-mismatch", public_view(verified))
code, redeemed, raw = proof(base, "/v1/tickets/redeem", bob_ticket, "tenant-a", "alice", "alice-session", "exec-bob", "rag.search")
check("redeem-other-tenant-rejected", code == 403 and redeemed.get("redeemed") is False, public_view(redeemed))
code, blank, raw = proof(base, "/v1/tickets/verify", bob_ticket, "", "bob", "bob-session", "exec-bob", "rag.search")
check("blank-binding-still-matches", code == 200 and blank.get("accepted") is True, public_view(blank))
code, redeemed, raw = proof(base, "/v1/tickets/redeem", bob_ticket, "tenant-b", "bob", "bob-session", "exec-bob", "rag.search")
check("owner-redeem-after-cross-attempt", code == 200 and redeemed.get("redeemed") is True, public_view(redeemed))

results = []
def concurrent(execution, token, tenant, user, session):
    results.append(issue(base, tenant, user, session, execution, "rag.search", token)[:2])
threads = [
    threading.Thread(target=concurrent, args=("exec-conc-a", alice, "tenant-a", "alice", "alice-session")),
    threading.Thread(target=concurrent, args=("exec-conc-b", bob, "tenant-b", "bob", "bob-session")),
]
for thread in threads:
    thread.start()
for thread in threads:
    thread.join()
check("concurrent-tenants-issue", len(results) == 2 and all(item[0] == 201 and item[1].get("issued") is True for item in results), [public_view(item[1]) for item in results])
conc = {item[1]["executionId"]: item[1]["ticket"] for item in results}
code, redeemed, raw = proof(base, "/v1/tickets/redeem", conc["exec-conc-b"], "tenant-a", "alice", "alice-session", "exec-conc-b", "rag.search")
check("concurrent-redeem-isolated", code == 403 and redeemed.get("redeemed") is False, public_view(redeemed))

code, audited, raw = call("GET", base + "/v1/tickets/audit")
check("audit-unauthenticated", code == 401 and "exec-" not in raw and "tenant-" not in raw, redact(raw))
code, audited, raw = call("GET", base + "/v1/tickets/audit?access_token=" + alice)
check("audit-query-ignored", code == 401 and "exec-" not in raw, redact(raw))
code, audited, raw = call("GET", base + "/v1/tickets/audit", headers={"Authorization": "Bearer " + alice})
alice_execs = {item.get("executionId") for item in audited} if isinstance(audited, list) else set()
alice_tenants = {item.get("tenant") for item in audited} if isinstance(audited, list) else set()
check("audit-alice-only", code == 200 and "exec-bob" not in alice_execs and "exec-mysql" in alice_execs and alice_tenants == {"tenant-a"}, public_view(audited))
code, audited, raw = call("GET", base + "/v1/tickets/audit", headers={"Cookie": "JASAGENT_SESSION=" + bob})
bob_execs = {item.get("executionId") for item in audited} if isinstance(audited, list) else set()
bob_tenants = {item.get("tenant") for item in audited} if isinstance(audited, list) else set()
check("audit-bob-cookie", code == 200 and "exec-mysql" not in bob_execs and "exec-bob" in bob_execs and bob_tenants == {"tenant-b"}, public_view(audited))

code, redeemed, raw = proof(base, "/v1/tickets/redeem", mysql_ticket, "tenant-a", "alice", "alice-session", "exec-mysql", "rag.search")
check("redeem-before-restart", code == 200 and redeemed.get("redeemed") is True, public_view(redeemed))
stop(server)
port = socket.socket()
port.bind(("127.0.0.1", 0))
pass_port = port.getsockname()[1]
port.close()
server, server_log = boot(pass_port, "pass-2.log")
base = f"http://127.0.0.1:{pass_port}"
pending = sql("SELECT decision FROM jasagent_agentpass_pending WHERE execution_id='exec-hold'")
check("pending-survives-restart", pending == "PENDING", pending)
used = sql("SELECT used FROM jasagent_agentpass_ticket WHERE execution_id='exec-mysql'")
check("used-flag-survives-restart", used == "1", used)
code, replay, raw = proof(base, "/v1/tickets/redeem", mysql_ticket, "tenant-a", "alice", "alice-session", "exec-mysql", "rag.search")
check("replay-after-restart", code == 409 and replay.get("reason") == "replay" and replay.get("redeemed") is False, public_view(replay))
code, redeemed, raw = proof(base, "/v1/tickets/redeem", fresh_ticket, "tenant-a", "alice", "alice-session", "exec-fresh", "rag.search")
check("unused-redeem-after-restart", code == 200 and redeemed.get("redeemed") is True, public_view(redeemed))
assert_clean(server_log)
stop(server)

missing_log = str(pathlib.Path(log_dir, "missing-key.log"))
missing_env = pass_env()
missing_env.pop("JASAGENT_AGENTPASS_SIGNING_KEY", None)
missing = start_java(
    ["java", "-jar", os.environ["PASS_JAR"], "--server.address=127.0.0.1", "--server.port=0", "--jasagent.home=" + home],
    missing_env, missing_log)
try:
    missing.wait(timeout=40)
except subprocess.TimeoutExpired:
    missing.kill()
    raise SystemExit("FAIL missing key did not exit")
text = pathlib.Path(missing_log).read_text(errors="replace")
check("missing-key-refuses-start", missing.returncode != 0 and "jasagent.agentpass.signing-key" in text and "process-local" not in text, "exit")
check("missing-key-log-clean", os.environ["DB_PASS"] not in text and os.environ["SIGN_KEY"] not in text, "log")

workspace = tempfile.mkdtemp(prefix="jasagent-023-ws-")
app_home = tempfile.mkdtemp(prefix="jasagent-023-app-home-")
workflow = pathlib.Path(workspace, "jasagent", "workflows", "call-rag.json")
workflow.parent.mkdir(parents=True)
workflow.write_text('{"id":"call-rag","nodes":[{"id":"search","type":"tool","tool":"rag.search"},{"id":"done","type":"end"}]}')
deny = pathlib.Path(app_home, "owner", "jasagent", "auth", "policies.yaml")
deny.parent.mkdir(parents=True)
deny.write_text("rules:\n  - tool: rag.search\n    decision: DENY\n")
internal = secrets_token()
secrets.append(internal)
app_port_sock = socket.socket()
app_port_sock.bind(("127.0.0.1", 0))
app_port = app_port_sock.getsockname()[1]
app_port_sock.close()
app_log = str(pathlib.Path(log_dir, "app.log"))
app_env = {
    "PATH": os.environ["PATH"],
    "HOME": os.environ.get("HOME", "/tmp"),
    "LANG": "C.UTF-8",
    "JASAGENT_JDBC_URL": os.environ["JDBC_URL"],
    "JASAGENT_JDBC_USER": "jasagent",
    "JASAGENT_JDBC_PASSWORD": os.environ["DB_PASS"],
    "JASAGENT_INTERNAL_TOKEN": internal,
}
# 胖包里的检查点存储器要打开内部加载器，和 022 验收进程相同，不是生产默认参数。
app = start_java(
    ["java",
     "--add-opens", "java.base/java.net=ALL-UNNAMED",
     "--add-opens", "java.base/jdk.internal.loader=ALL-UNNAMED",
     "-jar", os.environ["APP_JAR"], "server",
     "--server.address=127.0.0.1", "--server.port=" + str(app_port),
     "--jasagent.home=" + app_home, "--jasagent.egress.enabled=false"],
    app_env, app_log)
wait_http(app, f"http://127.0.0.1:{app_port}/health", app_log, 90)
assert_loopback(app_port)
sql(
    "INSERT INTO jasagent_account (login, password_hash, role, tenant_id, created_at) VALUES "
    "('owner','not-used','user','tenant-owner','2026-11-20T00:00:00Z'),"
    "('intruder','not-used','user','tenant-other','2026-11-20T00:00:00Z')"
)
sql(
    "INSERT INTO agent_workspace (tenant_id, user_id, workspace_path) VALUES "
    f"('tenant-owner','owner','{workspace}')"
)
app_base = f"http://127.0.0.1:{app_port}"
def app_headers(user, tenant):
    return {
        "X-Jasagent-Token": internal,
        "X-Jasagent-User": user,
        "X-Jasagent-Tenant": tenant,
        "X-Jasagent-Session": "app-session",
    }
code, created, raw = call("POST", app_base + "/api/executions", {"workflowId": "call-rag", "input": "hello"}, app_headers("owner", "tenant-owner"))
check("execution-accepted", code == 202 and bool(created.get("executionId")), public_view(created))
execution_id = created["executionId"]
detail = {}
for _ in range(60):
    code, detail, raw = call("GET", app_base + "/api/executions/" + execution_id, headers=app_headers("owner", "tenant-owner"))
    if detail.get("status") in ("SUSPENDED", "FAILED", "SUCCEEDED"):
        break
    time.sleep(0.5)
passes = detail.get("passes") or []
pending_pass = next((item for item in passes if item.get("name") == "rag.search"), {})
check("approval-required", detail.get("status") == "SUSPENDED" and pending_pass.get("decision") == "PENDING" and pending_pass.get("reason") == "approval-required", str(public_view(detail)) + "\n" + tail_safe(app_log))
pending_dir = pathlib.Path(app_home, "owner", "jasagent", "auth", "pending")
check("no-home-pending", not pending_dir.exists(), str(pending_dir))
row = sql(f"SELECT decision, tenant_id, user_id FROM jasagent_agentpass_pending WHERE execution_id='{execution_id}'")
check("execution-pending-row", row == "PENDING\ttenant-owner\towner", row)
code, denied, raw = call("POST", app_base + f"/api/executions/{execution_id}/passes/{pending_pass['passId']}", {"decision": "ALLOW"}, app_headers("intruder", "tenant-other"))
check("other-user-cannot-approve", code == 401, public_view(denied))
row = sql(f"SELECT decision FROM jasagent_agentpass_pending WHERE pass_id='{pending_pass['passId']}'")
check("pending-unchanged", row == "PENDING", row)
code, allowed, raw = call("POST", app_base + f"/api/executions/{execution_id}/passes/{pending_pass['passId']}", {"decision": "ALLOW"}, app_headers("owner", "tenant-owner"))
check("owner-approves-existing-path", code == 200, public_view(allowed))
done = {}
for _ in range(60):
    code, done, raw = call("GET", app_base + "/api/executions/" + execution_id, headers=app_headers("owner", "tenant-owner"))
    if done.get("status") in ("SUCCEEDED", "FAILED"):
        break
    time.sleep(0.5)
check("same-execution-resumes", done.get("executionId") == execution_id and done.get("status") == "SUCCEEDED" and done.get("retrievals"), public_view(done))
row = sql(f"SELECT decision FROM jasagent_agentpass_pending WHERE pass_id='{pending_pass['passId']}'")
check("approved-row", row == "ALLOW", row)
assert_clean(app_log)
stop(app)
shutil.rmtree(home, ignore_errors=True)
shutil.rmtree(app_home, ignore_errors=True)
shutil.rmtree(workspace, ignore_errors=True)
shutil.rmtree(log_dir, ignore_errors=True)
print("PASS process-tenant-restart-audit-approval", flush=True)
PY
pass "进程级验收通过"

section "部署留下的条目"
printf '%s\n' "第 10 到第 12 条留给部署：不改 deploy/k8s，不改 probe-egress.sh，不写 deploy.md。生产要另补签名密钥 Secret 和 agentpass 的 JDBC 环境；当前探针仍写 home 文件。agentpass 不暴露公网，不加中间件。"
pass "部署条目未在本脚本执行"
