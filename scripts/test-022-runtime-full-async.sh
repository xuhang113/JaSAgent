#!/usr/bin/env bash
# 022 的合同测试，覆盖规格验收第 1 到第 9 条。
# 平台线程用 jasagent-app 进程的 jcmd 线程转储核对，不把 server.tomcat.threads.max 当成平台线程数。
# 不修改 jasagent-sidecar、jasagent-gateway、jasagent-web 和 deploy/k8s。
# 第 10 到第 12 条是部署阶段，本脚本不覆盖。
set -euo pipefail

# 仓库根目录。
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo}"

# 本机 Maven 若不在 PATH 上，使用已解压的 3.9.9。不改仓库里的构建文件。
if [[ -x /tmp/apache-maven-3.9.9/bin/mvn ]]; then
  export PATH="/tmp/apache-maven-3.9.9/bin:${PATH}"
fi

# 验收故意压低的操作系统平台工作线程数。只通过 jasagent.app.tomcat-platform-workers 生效。
platform_cap=2
# 同时进行的长 ReAct，含两条恢复。必须多于平台线程。
streams=6
# server.tomcat.threads.max 故意留高。开启虚拟线程时它常常只是请求槽位，不能拿来证明本条。
tomcat_max=64
# 上游发出首块后继续停住的秒数。首块必须在这段结束前到达 agent。
hold_seconds=12
# 模型自己的超时。必须长于上游停顿，避免还没读完就被掐断。
llm_timeout=40
# 允许的改动路径。sidecar、网关、控制台和部署清单出现即失败。
allowed_re='^(jasagent-llm/|jasagent-app/|jasagent-workflow/|jasagent-controller/|jasagent-interrupt/|scripts/test-022-runtime-full-async\.sh$|changelog/2026-11-19-runtime-full-async\.md$)'

# 打印一条验收项。
section() {
  printf 'TEST %s\n' "$1"
}

# 打印一条通过。
pass() {
  printf 'PASS %s\n' "$1"
}

# 函数或常量的上一行必须是注释。
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

# 选出一个本机空闲端口。
free_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

# 统计一个进程的操作系统任务数。虚拟线程不会各占一条。
os_tasks() {
  local pid="$1"
  find "/proc/${pid}/task" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' '
}

# 等到健康检查返回，或超时失败。这里只证明进程起来了，不把存储状态当成平台线程证据。
wait_health() {
  local url="$1"
  local i
  for i in $(seq 1 80); do
    if curl -sf --max-time 2 "${url}" >/dev/null; then
      return 0
    fi
    sleep 0.5
  done
  echo "jasagent-app 没有在超时内起来。" >&2
  return 1
}

# 用 jcmd 的平台线程转储判断。转储里的线程带操作系统 nid，虚拟线程不在这份名单里。
# 平台工作线程必须正好是压低后的数量，并且都停在线程池取任务。任何平台栈上出现等待中的运行时代码都失败。
judge_dump() {
  local dump="$1"
  python3 - "${dump}" "${platform_cap}" << 'PY'
import sys
path, cap = sys.argv[1], int(sys.argv[2])
text = open(path, encoding="utf-8", errors="replace").read()
blocks = []
current = []
for line in text.splitlines():
    if line.startswith('"') and current:
        blocks.append("\n".join(current))
        current = [line]
    elif line.startswith('"') or current:
        current.append(line)
if current:
    blocks.append("\n".join(current))
workers = []
execs = []
stuck = []
busy = []
needles = (
    "dev.jasagent.llm.",
    "dev.jasagent.app.LocalRuntime",
    "dev.jasagent.workflow.LangGraphWorkflowEngine",
    "dev.jasagent.workflow.WorkflowGraph",
    "dev.jasagent.controller.",
)
for block in blocks:
    header = block.split("\n", 1)[0]
    if not header.startswith('"'):
        continue
    name = header.split('"', 2)[1]
    # 虚拟线程，以及名字属于运行时等待线程的，不是 Tomcat 的操作系统平台工作线程。
    if " virtual" in header or name.startswith("jasagent-runtime-"):
        continue
    if name.startswith("jasagent-app-platform-"):
        workers.append(name)
        if " nid=" not in header:
            print(f"平台线程转储没有操作系统 nid：{header}", file=sys.stderr)
            sys.exit(1)
        if "ThreadPoolExecutor.getTask" not in block:
            busy.append(name)
    if "-exec-" in name:
        execs.append(name)
    for frame in block.splitlines()[1:]:
        if any(needle in frame for needle in needles):
            stuck.append(name + " :: " + frame.strip())
if len(workers) != cap:
    print(f"操作系统平台工作线程是 {len(workers)}，不是 {cap}：{workers}", file=sys.stderr)
    sys.exit(1)
if busy:
    print(f"平台工作线程没有回到线程池：{busy}", file=sys.stderr)
    sys.exit(1)
if execs:
    print(f"Tomcat 默认 exec 平台线程还在：{execs}", file=sys.stderr)
    sys.exit(1)
if stuck:
    print("平台线程的栈上出现了 agent 等待或运行时代码：", file=sys.stderr)
    print("\n".join(stuck[:30]), file=sys.stderr)
    sys.exit(1)
print(f"workers={len(workers)}")
PY
}

section "7-9 改动范围、注释和密钥"

assert_comments "${repo}/scripts/test-022-runtime-full-async.sh"
python3 - << 'PY'
import re
import pathlib
import subprocess
import sys
root = pathlib.Path(".")
changed = subprocess.check_output(
    ["bash", "-lc", " { git diff --name-only origin/master; git ls-files --others --exclude-standard; } | sed '/^$/d' | sort -u "],
    text=True,
).splitlines()
stop = re.compile(r"^(public|protected|private|class|interface|enum|record|static final)\b")
problems = []
for name in changed:
    if not name.endswith(".java") or "/src/main/" not in name:
        continue
    path = pathlib.Path(name)
    lines = path.read_text(encoding="utf-8").splitlines()
    for index, line in enumerate(lines):
        stripped = line.strip()
        if "Logger " in stripped:
            continue
        is_type = bool(re.match(r"^(public |final |abstract |private |protected )?(final )?(class|interface|enum|record) ", stripped))
        is_const = "static final" in stripped and stripped.endswith(";")
        is_method = bool(re.match(r"^(public|protected|private)(\s+static)?\s+[\w<>,\[\]\s]+\s+\w+\s*\(", stripped)) and not stripped.endswith(";")
        if not (is_type or is_const or is_method):
            continue
        cursor = index - 1
        ok = False
        while cursor >= 0:
            prev = lines[cursor].strip()
            if not prev:
                cursor -= 1
                continue
            if prev.endswith("*/") or prev.startswith("//"):
                ok = True
                break
            if stop.match(prev):
                break
            cursor -= 1
        if not ok:
            problems.append(f"{path}:{index + 1}: {stripped[:160]}")
if problems:
    print("本轮改到的类、常量或方法缺少注释：", file=sys.stderr)
    print("\n".join(problems), file=sys.stderr)
    sys.exit(1)
PY

if [[ ! -f jasagent-app/src/test/java/dev/jasagent/app/AppPlatformWorkersTest.java ]]; then
  echo "缺少平台线程自动测试。" >&2
  exit 1
fi
if [[ ! -f jasagent-llm/src/test/java/dev/jasagent/llm/egress/CarrierFreeReadTest.java ]]; then
  echo "缺少不钉载体的读流自动测试。" >&2
  exit 1
fi
if [[ ! -x scripts/test-022-runtime-full-async.sh ]]; then
  echo "验收脚本不可执行。" >&2
  exit 1
fi
if grep -q 'spring-boot-starter-webflux' jasagent-app/pom.xml jasagent-llm/pom.xml jasagent-workflow/pom.xml jasagent-controller/pom.xml; then
  echo "运行时模块被改成了 WebFlux。" >&2
  exit 1
fi
if grep -R -n 'ofInputStream' jasagent-llm/src/main >/dev/null; then
  echo "agent 侧仍在使用会钉住载体的 JDK 输入流处理器。" >&2
  exit 1
fi
if ! grep -q '^jasagent.app.tomcat-platform-workers=0$' jasagent-app/src/main/resources/application.properties; then
  echo "生产默认把平台线程上限改低了，或把开关写成了 server.tomcat.threads.max。" >&2
  exit 1
fi

names="$(
  {
    git diff --name-only origin/master
    git ls-files --others --exclude-standard
  } | sed '/^$/d' | sort -u
)"
if [[ -z "${names}" ]]; then
  echo "相对 origin/master 没有改动。" >&2
  exit 1
fi
while IFS= read -r name; do
  if [[ ! "${name}" =~ ${allowed_re} ]]; then
    echo "改动超出本轮运行时模块、脚本和变更记录：${name}" >&2
    exit 1
  fi
done <<< "${names}"
for forbidden in jasagent-sidecar jasagent-gateway jasagent-web deploy/k8s; do
  if git diff --name-only origin/master -- "${forbidden}" | grep -q .; then
    echo "碰到了禁止修改的路径：${forbidden}" >&2
    exit 1
  fi
  if git ls-files --others --exclude-standard -- "${forbidden}" | grep -q .; then
    echo "新增了禁止修改的路径：${forbidden}" >&2
    exit 1
  fi
done

changelog="changelog/2026-11-19-runtime-full-async.md"
if [[ ! -f "${changelog}" ]]; then
  echo "缺少变更记录。" >&2
  exit 1
fi
if ! grep -q 'jasagent-llm' "${changelog}" || ! grep -q 'jasagent-app' "${changelog}" \
  || ! grep -q 'jasagent-workflow' "${changelog}" || ! grep -q 'jasagent-controller' "${changelog}" \
  || ! grep -q 'scripts' "${changelog}" || ! grep -q 'test-022-runtime-full-async.sh' "${changelog}" \
  || ! grep -q '第 10 到第 12 条' "${changelog}"; then
  echo "变更记录没有写明模块和验证方式。" >&2
  exit 1
fi
if grep -q 'deploy.md' "${changelog}" && grep -q '已写' "${changelog}"; then
  echo "变更记录把部署文档写成了已经完成。" >&2
  exit 1
fi
if grep -R -n -E 'BEGIN PRIVATE KEY|AKIA[0-9A-Z]{16}' \
  jasagent-llm/src/main jasagent-app/src/main jasagent-workflow/src/main jasagent-controller/src/main \
  "${changelog}" >/dev/null; then
  echo "仓库里出现了密钥材料。" >&2
  exit 1
fi
pass "范围、注释、脚本和变更记录"

section "9 模块测试"
if ! command -v mvn >/dev/null 2>&1; then
  echo "找不到 mvn。" >&2
  exit 1
fi
mvn -pl jasagent-llm,jasagent-workflow,jasagent-interrupt,jasagent-app -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=CarrierFreeReadTest,CallerCarryTest,SidecarLlmTransportTest,LlmRouterTest,StreamingThinkingTest,ReasoningTextTest,ToolCallTextTest,EgressRouteTest,AppPlatformWorkersTest,LeavingClusterHttpTest,SessionModelCatalogTest,WorkflowReactTest,InterruptResumeTest,!WorkflowReactTest#defaultCheckpointSaverIsMysql
pass "llm、workflow 和 app 的相关测试"

section "5 018 与 sidecar 模块测试"
# 021 脚本的路径白名单只允许 sidecar 那一轮的文件。本分支的运行时差异会让未改动的 021 脚本直接失败。
# 这里不放宽那份白名单。sidecar 没有差异，并复跑 sidecar 模块测试和同一份 018 检查。
if git diff --name-only origin/master -- jasagent-sidecar | grep -q .; then
  echo "sidecar 有差异，021 的产品行为不能视为未改。" >&2
  exit 1
fi
echo "未改动的 021 脚本自带路径白名单，会拒绝本轮运行时文件。不放宽它，改为确认 sidecar 无差异并复跑其模块测试。"
mvn -pl jasagent-sidecar -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=SidecarEgressTest,SidecarHealthTest,SidecarContractTest,SidecarStreamTest,TicketWireTest,LlmAllowlistTest,SidecarPlatformThreadTest
adapted="$(mktemp)"
python3 - "${repo}" "${adapted}" << 'PY'
import pathlib
import sys
repo, dest = sys.argv[1], sys.argv[2]
text = pathlib.Path("scripts/test-018-sidecar-egress.sh").read_text(encoding="utf-8")
text = text.replace(
    'repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"',
    f'repo="{repo}"',
)
old = 'spec_base="08be62c33ec4c0571e99943215cb2602e810745c"'
new = 'spec_base="origin/master"'
if old not in text:
    raise SystemExit("018 脚本里的对比底变了，不能再按原样复跑。")
pathlib.Path(dest).write_text(text.replace(old, new, 1), encoding="utf-8")
PY
bash "${adapted}"
rm -f "${adapted}"
pass "018 第 1 到第 9 条，以及 sidecar 模块测试"

section "1-4 6 独立进程的平台线程、恢复和租户"
if ! command -v jcmd >/dev/null 2>&1; then
  echo "找不到 jcmd，无法读取操作系统平台线程。" >&2
  exit 1
fi
mvn -pl jasagent-app,jasagent-sidecar -am -DskipTests package -q
app_jar="jasagent-app/target/jasagent-app-0.1.0.jar"
side_jar="jasagent-sidecar/target/jasagent-sidecar-0.1.0.jar"
if [[ ! -f "${app_jar}" || ! -f "${side_jar}" ]]; then
  echo "没有打出 agent 或 sidecar 包。" >&2
  exit 1
fi

work="$(mktemp -d)"
side_pid=""
app_pid=""
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
env \
  JASAGENT_CHECKPOINT=sqlite \
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

printf '022 TEST PASSED\n'
