#!/usr/bin/env python3
# 023 ATDD 进程级验收正文。从官方合同脚本独立抽出，不调用该脚本。
# 环境变量由 tests/live.sh 注入，密钥只在本次进程里。
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