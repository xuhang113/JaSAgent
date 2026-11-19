up_pid=""
# 结束时关掉本轮拉起的进程，避免占住端口。不把内部令牌写进退出信息。
cleanup() {
  local status=$?
  if [[ "${status}" -ne 0 && -n "${work:-}" && -d "${work}" ]]; then
    mkdir -p /tmp/jasagent-022-last
    if [[ -f "${work}/app.log" && -n "${token:-}" ]]; then
      grep -v -F "${token}" "${work}/app.log" > /tmp/jasagent-022-last/app.log || true
    fi
    if [[ -f "${work}/sidecar.log" ]]; then
      cp "${work}/sidecar.log" /tmp/jasagent-022-last/sidecar.log || true
    fi
    if [[ -f "${work}/stream.dump" ]]; then
      cp "${work}/stream.dump" /tmp/jasagent-022-last/stream.dump || true
    fi
  fi
  if [[ -n "${app_pid}" ]]; then
    kill "${app_pid}" 2>/dev/null || true
    wait "${app_pid}" 2>/dev/null || true
  fi
  if [[ -n "${side_pid}" ]]; then
    kill "${side_pid}" 2>/dev/null || true
    wait "${side_pid}" 2>/dev/null || true
  fi
  if [[ -n "${up_pid}" ]]; then
    kill "${up_pid}" 2>/dev/null || true
    wait "${up_pid}" 2>/dev/null || true
  fi
  rm -rf "${work}"
}
trap cleanup EXIT

if ! grep -qE '(^|[[:space:]])slow\.test([[:space:]]|$)' /etc/hosts; then
  if ! printf '127.0.0.1 slow.test\n' | sudo tee -a /etc/hosts >/dev/null; then
    echo "无法把 slow.test 解析到回环。出站必须走非回环主机名才会进入 sidecar。" >&2
    exit 1
  fi
fi

up_port="$(free_port)"
side_port="$(free_port)"
app_port="$(free_port)"
token="$(python3 -c 'import secrets; print(secrets.token_hex(16))')"
install -d -m 700 "${work}/up" "${work}/home" "${work}/ws-a/jasagent/workflows" "${work}/ws-b/jasagent/workflows"
for ws in "${work}/ws-a" "${work}/ws-b"; do
  cat > "${ws}/jasagent/workflows/slow-react.json" << 'JSON'
{"id":"slow-react","nodes":[{"id":"act","type":"react","maxSteps":1,"tools":[],"grounding":false},{"id":"done","type":"end"}]}
JSON
  cat > "${ws}/jasagent/workflows/resume-react.json" << 'JSON'
{"id":"resume-react","nodes":[{"id":"pause","type":"interrupt"},{"id":"act","type":"react","maxSteps":1,"tools":[],"grounding":false},{"id":"done","type":"end"}]}
JSON
  cat > "${ws}/jasagent/workflows/end-only.json" << 'JSON'
{"id":"end-only","nodes":[{"id":"done","type":"end"}]}
JSON
done

python3 - "${up_port}" "${hold_seconds}" "${work}/up" << 'PY' &
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

port = int(sys.argv[1])
hold = float(sys.argv[2])
stamp = Path(sys.argv[3])

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        self._send(200, "application/json", b'{"data":[]}')

    def do_POST(self):
        raw = self._read_body()
        mark = re.search(br"MARK-[A-Za-z0-9-]+", raw)
        host = self.headers.get("Host", "")
        (stamp / "seen.txt").open("a", encoding="utf-8").write(host + " " + self.path + "\n")
        if mark is None:
            self._send(200, "application/json", b'{"ok":true}')
            return
        marker = mark.group().decode("ascii")
        (stamp / ("started-" + marker)).write_text("1", encoding="utf-8")
        first = (
            'data: {"id":"c","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"'
            + marker
            + '"}}]}\n\n'
        ).encode("utf-8")
        rest = (
            'data: {"id":"c","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":" END"}}]}\n\n'
            "data: [DONE]\n\n"
        ).encode("utf-8")
        self.close_connection = True
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(first)
        self.wfile.flush()
        time.sleep(hold)
        self.wfile.write(rest)
        self.wfile.flush()
        (stamp / ("done-" + marker)).write_text("1", encoding="utf-8")

    def _read_body(self):
        length = self.headers.get("Content-Length")
        if length is not None:
            return self.rfile.read(int(length))
        if "chunked" not in (self.headers.get("Transfer-Encoding") or "").lower():
            return b""
        raw = b""
        while True:
            line = self.rfile.readline()
            size = int(line.split(b";", 1)[0], 16)
            if size == 0:
                while True:
                    trailer = self.rfile.readline()
                    if trailer in (b"\r\n", b"\n", b""):
                        break
                return raw
            raw += self.rfile.read(size)
            self.rfile.read(2)

    def _send(self, status, content_type, body):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()

    def log_message(self, fmt, *args):
        return

ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
PY
up_pid=$!

java -jar "${side_jar}" \
  --server.address=127.0.0.1 \
  --server.port="${side_port}" \
  --jasagent.sidecar.llm-allowlist=slow.test \
  --jasagent.sidecar.upstream-timeout-millis=45000 \
  > "${work}/sidecar.log" 2>&1 &
side_pid=$!

# 打包后的进程要打开 SQLite 检查点时，现有保存器会反射平台类加载器。这两个打开只给验收进程，不写进生产配置。
# 只设 JASAGENT_CHECKPOINT=sqlite 不够：本机若能发现 MySQL，LocalRuntime 会在检查点 JDBC 地址为空时改回 mysql。
# 这里补上 sqlite 地址，避免验收落到共用的本机 MySQL，并发释放检查点时死锁。
env \
  JASAGENT_CHECKPOINT=sqlite \
  JASAGENT_CHECKPOINT_JDBC_URL="jdbc:sqlite:${work}/checkpoint.db" \
  JASAGENT_GATEWAY_BASE_URL="http://slow.test:${up_port}" \
  JASAGENT_GATEWAY_API_KEY="placeholder-key" \
  JASAGENT_GATEWAY_MODEL="slow-model" \
  JASAGENT_LLM_TIMEOUT_SECONDS="${llm_timeout}" \
  java -Djdk.tracePinnedThreads=full \
  --add-opens java.base/java.net=ALL-UNNAMED \
  --add-opens java.base/jdk.internal.loader=ALL-UNNAMED \
  -jar "${app_jar}" \
  --server.address=127.0.0.1 \
  --server.port="${app_port}" \
  --server.tomcat.threads.max="${tomcat_max}" \
  --spring.threads.virtual.enabled=false \
  --jasagent.app.tomcat-platform-workers="${platform_cap}" \
  --jasagent.home="${work}/home" \
  --jasagent.workspaces="${work}/ws-a,${work}/ws-b" \
  --jasagent.internal.token="${token}" \
  --jasagent.sidecar.url="http://127.0.0.1:${side_port}" \
  --jasagent.egress.enabled=true \
  > "${work}/app.log" 2>&1 &
app_pid=$!

# 失败时把日志尾部打出来，但先去掉内部令牌，避免它进入验收输出。
show_logs() {
  if [[ -f "${work}/app.log" ]]; then
    grep -v -F "${token}" "${work}/app.log" | tail -n 50 >&2 || true
  fi
  if [[ -f "${work}/sidecar.log" ]]; then
    tail -n 30 "${work}/sidecar.log" >&2 || true
  fi
}

if ! wait_health "http://127.0.0.1:${app_port}/health"; then
  show_logs
  exit 1
fi
python3 - "${app_pid}" "${tomcat_max}" "${platform_cap}" "${streams}" << 'PY'
import pathlib
import sys
pid, tomcat_max, cap, streams = sys.argv[1:]
text = pathlib.Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b"\n").decode("utf-8", "replace")
need = [
    f"server.tomcat.threads.max={tomcat_max}",
    f"jasagent.app.tomcat-platform-workers={cap}",
    "spring.threads.virtual.enabled=false",
]
missing = [item for item in need if item not in text]
if missing or int(tomcat_max) <= int(streams) or int(streams) <= int(cap):
    print("进程没有同时带上高的 threads.max 和压低的平台线程数。", file=sys.stderr)
    sys.exit(1)
PY

# 只把本轮生成的内部令牌交给验收进程，不写进仓库。
JASAGENT_ACCEPT_TOKEN="${token}" python3 - "${work}" "${app_port}" "${app_pid}" "${streams}" "${hold_seconds}" "${llm_timeout}" << 'PY'
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

work = Path(sys.argv[1])
port = sys.argv[2]
pid = sys.argv[3]
streams = int(sys.argv[4])
hold = float(sys.argv[5])
llm_timeout = float(sys.argv[6])
token = os.environ["JASAGENT_ACCEPT_TOKEN"]
base = f"http://127.0.0.1:{port}"

def call(method, path, tenant, session, body=None, timeout=5):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(base + path, data=data, method=method)
    request.add_header("X-Jasagent-Token", token)
    request.add_header("X-Jasagent-Tenant", tenant)
    request.add_header("X-Jasagent-User", "shared-user")
    request.add_header("X-Jasagent-Session", session)
    if body is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
            return response.status, raw
    except urllib.error.HTTPError as ex:
        raw = ex.read().decode("utf-8", "replace")
        return ex.code, raw
    except (TimeoutError, urllib.error.URLError):
        return 0, ""

def must(status, method, path, tenant, session, body=None, timeout=5):
    code, raw = call(method, path, tenant, session, body, timeout)
    if code != status:
        print(f"{method} {path} 返回 {code}，期望 {status}", file=sys.stderr)
        sys.exit(1)
    return json.loads(raw) if raw else {}

for tenant, workspace in (("tenant-a", work / "ws-a"), ("tenant-b", work / "ws-b")):
    must(200, "POST", "/api/workspace", tenant, "sess-" + tenant[-1], {"path": str(workspace)})

session = must(200, "POST", "/api/sessions", "tenant-a", "sess-a", {"title": "avail"})
session_id = session["sessionId"]

jobs = [
    ("tenant-a", "sess-a", "resume-react", "MARK-A-RESUME"),
    ("tenant-b", "sess-b", "resume-react", "MARK-B-RESUME"),
    ("tenant-a", "sess-a", "slow-react", "MARK-A-1"),
    ("tenant-a", "sess-a", "slow-react", "MARK-A-2"),
    ("tenant-b", "sess-b", "slow-react", "MARK-B-1"),
    ("tenant-b", "sess-b", "slow-react", "MARK-B-2"),
]
resumes = []
for tenant, session_name, workflow, marker in jobs[:2]:
    created = must(202, "POST", "/api/executions", tenant, session_name, {"workflowId": workflow, "input": marker})
    resumes.append((tenant, session_name, created["executionId"], marker))

deadline = time.time() + 20
while time.time() < deadline:
    ready = 0
    for tenant, session_name, execution_id, marker in resumes:
        code, raw = call("GET", "/api/executions/" + execution_id, tenant, session_name, timeout=3)
        if code == 200 and '"status":"SUSPENDED"' in raw:
            ready += 1
    if ready == len(resumes):
        break
    time.sleep(0.2)
else:
    print("恢复路径没有先停在 SUSPENDED。", file=sys.stderr)
    sys.exit(1)
time.sleep(1)

import threading
resume_codes = []

def resume(tenant, session_name, execution_id):
    code, raw = call("POST", f"/api/executions/{execution_id}/resume", tenant, session_name, {}, timeout=hold + llm_timeout)
    resume_codes.append((execution_id, code, raw))

threads = []
for tenant, session_name, execution_id, marker in resumes:
    thread = threading.Thread(target=resume, args=(tenant, session_name, execution_id), daemon=True)
    thread.start()
    threads.append(thread)

slow = []
for tenant, session_name, workflow, marker in jobs[2:]:
    created = must(202, "POST", "/api/executions", tenant, session_name, {"workflowId": workflow, "input": marker})
    slow.append((tenant, session_name, created["executionId"], marker))
watched = resumes + slow
if len(watched) != streams:
    print("并发长调用数量不对。", file=sys.stderr)
    sys.exit(1)

deadline = time.time() + hold
seen = False
while time.time() < deadline:
    hit = 0
    for tenant, session_name, execution_id, marker in watched:
        code, raw = call("GET", "/api/executions/" + execution_id, tenant, session_name, timeout=3)
        if code == 200 and marker in raw and '"output"' in raw:
            try:
                output = json.loads(raw).get("output") or ""
            except json.JSONDecodeError:
                output = ""
            if marker in output:
                hit += 1
    done = len(list((work / "up").glob("done-*")))
    if hit == streams and done < streams:
        seen = True
        break
    time.sleep(0.2)
if not seen:
    done = len(list((work / "up").glob("done-*")))
    print(f"首块没有在上游结束前到达。done={done}", file=sys.stderr)
    for tenant, session_name, execution_id, marker in watched:
        code, raw = call("GET", "/api/executions/" + execution_id, tenant, session_name, timeout=3)
        status = ""
        error = ""
        body = {}
        try:
            body = json.loads(raw)
            status = str(body.get("status", ""))
            error = str(body.get("errorCode", ""))
        except json.JSONDecodeError:
            status = str(code)
        logs = ""
        try:
            logs = str(body.get("logs") or "").splitlines()[-4:]
        except Exception:
            logs = []
        print(f"{marker} status={status} error={error} logs={logs}", file=sys.stderr)
    sys.exit(1)

import subprocess
dump = work / "stream.dump"
subprocess.check_call(["jcmd", pid, "Thread.print"], stdout=dump.open("w", encoding="utf-8"))
baseline = len(list(Path(f"/proc/{pid}/task").iterdir()))

health_code, health_body = call("GET", "/health", "tenant-a", "sess-a", timeout=2)
if health_code != 200 or '"status":"ok"' not in health_body:
    print(f"长调用期间 /health 没有在超时内可用：{health_code}", file=sys.stderr)
    sys.exit(1)
created = must(202, "POST", "/api/executions", "tenant-a", "sess-a", {"workflowId": "end-only", "input": "avail"}, timeout=3)
if "executionId" not in created:
    print("长调用期间新的提交没有返回执行编号。", file=sys.stderr)
    sys.exit(1)
message = must(
    200,
    "POST",
    "/api/sessions/" + session_id + "/messages",
    "tenant-a",
    session_id,
    {"text": "still-here", "workflowId": "end-only"},
    timeout=3,
)
if "executionId" not in message:
    print("长调用期间新的发消息没有返回执行编号。", file=sys.stderr)
    sys.exit(1)

foreign_code, _ = call("GET", "/api/executions/" + resumes[0][2], "tenant-b", "sess-b", timeout=3)
if foreign_code != 401:
    print(f"另一租户读到了这条执行：{foreign_code}", file=sys.stderr)
    sys.exit(1)

for thread in threads:
    thread.join(timeout=hold + llm_timeout + 5)
if len(resume_codes) != len(resumes):
    print("恢复请求没有在超时内返回。", file=sys.stderr)
    sys.exit(1)
for execution_id, code, raw in resume_codes:
    if code != 200:
        print(f"恢复请求返回 {code}", file=sys.stderr)
        sys.exit(1)

for tenant, session_name, execution_id, marker in watched:
    deadline = time.time() + 20
    final = ""
    body = {}
    while time.time() < deadline:
        code, raw = call("GET", "/api/executions/" + execution_id, tenant, session_name, timeout=3)
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = {}
        final = str(body.get("status", ""))
        if final in {"SUCCEEDED", "FAILED", "CANCELLED", "OFFLINE"}:
            break
        time.sleep(0.3)
    if final != "SUCCEEDED":
        print(f"{marker} 没有成功结束：{final} {body.get('errorCode', '')}", file=sys.stderr)
        sys.exit(1)
    output = body.get("output") or ""
    logs = body.get("logs") or ""
    own = "caller-scope " + tenant + " shared-user " + session_name
    other = "tenant-b" if tenant == "tenant-a" else "tenant-a"
    if marker not in output or own not in logs or ("caller-scope " + other) in logs:
        print(f"{marker} 的上下文没有留在自己的执行上。output_has={marker in output} scope_has={own in logs}", file=sys.stderr)
        sys.exit(1)
    foreign = "MARK-B" if marker.startswith("MARK-A") else "MARK-A"
    if foreign in output:
        print(f"{marker} 的输出里出现了另一路的标记。", file=sys.stderr)
        sys.exit(1)

seen_hosts = (work / "up" / "seen.txt").read_text(encoding="utf-8") if (work / "up" / "seen.txt").exists() else ""
if "slow.test" not in seen_hosts or "chat/completions" not in seen_hosts:
    print("上游没有看到发往 slow.test 的补全请求。", file=sys.stderr)
    sys.exit(1)
print(f"baselineTasks={baseline}")
PY
judge_dump "${work}/stream.dump" > "${work}/stream.judge"
python3 - "${work}/app.log" << 'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
if "Pinned:" in text:
    print("日志里出现了 Pinned:。", file=sys.stderr)
    sys.exit(1)
blocks = []
current = []
for line in text.splitlines():
    if line.startswith("VirtualThread[") and "reason:MONITOR" in line:
        if current:
            blocks.append(current)
        current = [line]
        continue
    if not current:
        continue
    if line.startswith((" ", "\t")):
        current.append(line)
    else:
        blocks.append(current)
        current = []
if current:
    blocks.append(current)
own = [block for block in blocks if any("dev.jasagent" in line for line in block)]
sqlite = [block for block in blocks if block not in own and any("org.sqlite" in line for line in block)]
print(f"pin-blocks own={len(own)} sqlite-loader={len(sqlite)}")
if own:
    print("本轮代码在等待时钉住了载体：", file=sys.stderr)
    print("\n".join(own[0][:16]), file=sys.stderr)
    sys.exit(1)
PY
if grep -F "${token}" "${work}/app.log" >/dev/null || grep -F "${token}" "${work}/sidecar.log" >/dev/null; then
  echo "日志里出现了内部令牌。" >&2
  exit 1
fi
if ! grep -F 'egress forwarded host=slow.test' "${work}/sidecar.log" >/dev/null; then
  echo "长调用没有经过 sidecar 的 /v1/forward。" >&2
  show_logs
  exit 1
fi
printf 'ATDD-022 workers=%s tomcatMax=%s streams=%s hold=%s pinned=0\n' \
  "${platform_cap}" "${tomcat_max}" "${streams}" "${hold_seconds}"
pass "平台线程、恢复、首块、并发可用和租户隔离"
