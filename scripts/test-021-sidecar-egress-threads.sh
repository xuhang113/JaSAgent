#!/usr/bin/env bash
# 021 的合同测试，覆盖规格验收第 1 到第 9 条。
# 平台线程用独立 sidecar 进程的 jcmd 线程转储和 /proc/<pid>/task 点数。
# 不把 server.tomcat.threads.max 当成平台线程数，也不修改 deploy/k8s。
# 第 10 到第 12 条是部署阶段，本脚本不覆盖。
set -euo pipefail

# 仓库根目录。
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo}"

# 验收故意压低的操作系统平台工作线程数。
platform_cap=2
# 同时进行的长流式转发。必须多于平台线程。
streams=6
# server.tomcat.threads.max 故意留高，避免把它当成请求槽位后误判。
tomcat_max=64
# 上游两块之间的停顿秒数。首块必须在这段结束前到达。
hold_seconds=4
# 允许的改动路径。其余路径出现即失败。
allowed_re='^(jasagent-sidecar/|scripts/test-021-sidecar-egress-threads\.sh$|changelog/2026-11-18-sidecar-egress-threads\.md$)'

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

# 等到健康检查返回，或超时失败。
wait_health() {
  local url="$1"
  local i
  for i in $(seq 1 50); do
    if curl -sf --max-time 1 "${url}" >/dev/null; then
      return 0
    fi
    sleep 0.2
  done
  echo "sidecar 没有在超时内起来。" >&2
  return 1
}

# 用 jcmd 的平台线程转储判断。转储里的线程带操作系统 nid，虚拟线程不在这份名单里。
# 平台工作线程必须正好是压低后的数量，并且都停在线程池取任务，而不是停在 sidecar 里。
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
for block in blocks:
    header = block.split("\n", 1)[0]
    if not header.startswith('"'):
        continue
    name = header.split('"', 2)[1]
    # 出站虚拟线程和转储里标明 virtual 的线程不是 Tomcat 的操作系统平台工作线程。
    if " virtual" in header or name.startswith("jasagent-egress-"):
        continue
    if name.startswith("jasagent-tomcat-platform-"):
        workers.append(name)
        if " nid=" not in header:
            print(f"平台线程转储没有操作系统 nid：{header}", file=sys.stderr)
            sys.exit(1)
        if "ThreadPoolExecutor.getTask" not in block:
            busy.append(name)
    if "-exec-" in name:
        execs.append(name)
    for frame in block.splitlines()[1:]:
        if "dev.jasagent.sidecar." in frame:
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
    print("平台线程的栈上出现了 sidecar，等待或抄写钉住了载体：", file=sys.stderr)
    print("\n".join(stuck[:30]), file=sys.stderr)
    sys.exit(1)
print(f"workers={len(workers)}")
PY
}

section "7-9 改动范围、注释和密钥"

assert_comments "${repo}/scripts/test-021-sidecar-egress-threads.sh"
python3 - << 'PY'
import re
import pathlib
import sys
root = pathlib.Path("jasagent-sidecar/src/main/java")
stop = re.compile(r"^(public|protected|private|class|interface|enum|record|static final)\b")
problems = []
for path in root.rglob("*.java"):
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
    print("sidecar 的类、常量或方法缺少注释：", file=sys.stderr)
    print("\n".join(problems), file=sys.stderr)
    sys.exit(1)
PY

if [[ ! -f jasagent-sidecar/src/test/java/dev/jasagent/sidecar/SidecarPlatformThreadTest.java ]]; then
  echo "缺少平台线程自动测试。" >&2
  exit 1
fi
if [[ ! -x scripts/test-021-sidecar-egress-threads.sh ]]; then
  echo "验收脚本不可执行。" >&2
  exit 1
fi
if grep -q 'spring-boot-starter-webflux' jasagent-sidecar/pom.xml; then
  echo "sidecar 被改成了 WebFlux。" >&2
  exit 1
fi
if grep -R -n -E 'readAllBytes|ofByteArray' jasagent-sidecar/src/main >/dev/null; then
  echo "sidecar 仍在整包读取正文。" >&2
  exit 1
fi
if ! grep -q '^jasagent.sidecar.tomcat-platform-workers=0$' jasagent-sidecar/src/main/resources/application.properties; then
  echo "生产默认把平台线程上限改低了。" >&2
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
    echo "改动超出 jasagent-sidecar、本轮脚本和变更记录：${name}" >&2
    exit 1
  fi
  if [[ "${name}" == deploy/k8s/* ]]; then
    echo "碰到了 deploy/k8s：${name}" >&2
    exit 1
  fi
done <<< "${names}"
if git diff --name-only origin/master -- deploy/k8s | grep -q .; then
  echo "deploy/k8s 有差异。" >&2
  exit 1
fi

changelog="changelog/2026-11-18-sidecar-egress-threads.md"
if [[ ! -f "${changelog}" ]]; then
  echo "缺少变更记录。" >&2
  exit 1
fi
if ! grep -q 'jasagent-sidecar' "${changelog}" || ! grep -q 'scripts' "${changelog}" \
  || ! grep -q 'deploy/k8s' "${changelog}" || ! grep -q 'test-018-sidecar-egress.sh' "${changelog}" \
  || ! grep -q '第 10 到第 12 条' "${changelog}"; then
  echo "变更记录没有写明模块和验证方式。" >&2
  exit 1
fi
if grep -R -n -E 'BEGIN PRIVATE KEY|AKIA[0-9A-Z]{16}' \
  jasagent-sidecar/src/main "${changelog}" >/dev/null; then
  echo "仓库里出现了密钥材料。" >&2
  exit 1
fi
pass "范围、注释、脚本和变更记录"

section "1-5 模块测试"
if ! command -v mvn >/dev/null 2>&1; then
  echo "找不到 mvn。" >&2
  exit 1
fi
mvn -pl jasagent-sidecar -am test -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=SidecarEgressTest,SidecarHealthTest,SidecarContractTest,SidecarStreamTest,TicketWireTest,LlmAllowlistTest,SidecarPlatformThreadTest
pass "模块测试"

section "1-4 独立进程的平台线程"
if ! command -v jcmd >/dev/null 2>&1; then
  echo "找不到 jcmd，无法读取操作系统平台线程。" >&2
  exit 1
fi
mvn -pl jasagent-sidecar -am -DskipTests package -q
jar="jasagent-sidecar/target/jasagent-sidecar-0.1.0.jar"
if [[ ! -f "${jar}" ]]; then
  echo "没有打出 sidecar 包。" >&2
  exit 1
fi

work="$(mktemp -d)"
side_pid=""
up_pid=""
# 结束时关掉本轮拉起的 sidecar 和上游，避免占住端口。
cleanup() {
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

up_port="$(free_port)"
side_port="$(free_port)"
python3 - "${up_port}" "${hold_seconds}" << 'PY' &
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

port = int(sys.argv[1])
hold = float(sys.argv[2])

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if self.path.startswith("/v1/tickets/verify"):
            time.sleep(hold)
            body = (
                '{"proofValid":false,"used":false,"expired":false,"userMatch":false,'
                '"targetMatch":false,"tenantMatch":false,"sessionMatch":false,'
                '"executionMatch":false,"accepted":false,"reason":"proof-invalid"}'
            ).encode()
            self._send(200, "application/json", body)
            return
        if self.path == "/hold":
            time.sleep(hold)
            self._send(200, "text/plain", b"held")
            return
        if self.path == "/fast":
            self._send(200, "text/plain", b"ok")
            return
        if self.path == "/slow":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", "8")
            self.end_headers()
            self.wfile.write(b"one\n")
            self.wfile.flush()
            time.sleep(hold)
            self.wfile.write(b"two\n")
            self.wfile.flush()
            return
        self._send(404, "text/plain", b"miss")

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

# 平台线程压到 platform_cap。threads.max 保持 tomcat_max，不能靠它证明本条。
java -Djdk.tracePinnedThreads=full -jar "${jar}" \
  --server.address=127.0.0.1 \
  --server.port="${side_port}" \
  --server.tomcat.threads.max="${tomcat_max}" \
  --spring.threads.virtual.enabled=false \
  --jasagent.sidecar.tomcat-platform-workers="${platform_cap}" \
  --jasagent.sidecar.llm-allowlist=127.0.0.1 \
  --jasagent.sidecar.agentpass-base-url="http://127.0.0.1:${up_port}" \
  --jasagent.sidecar.agentpass-timeout-millis=8000 \
  --jasagent.sidecar.upstream-timeout-millis=20000 \
  > "${work}/sidecar.log" 2>&1 &
side_pid=$!

base="http://127.0.0.1:${side_port}"
if ! wait_health "${base}/health"; then
  tail -n 40 "${work}/sidecar.log" >&2 || true
  exit 1
fi
cmdline="$(tr '\0' '\n' < "/proc/${side_pid}/cmdline")"
if [[ "${cmdline}" != *"server.tomcat.threads.max=${tomcat_max}"* \
  || "${cmdline}" != *"jasagent.sidecar.tomcat-platform-workers=${platform_cap}"* ]]; then
  echo "进程没有同时带上高的 threads.max 和压低的平台线程数。" >&2
  exit 1
fi
if (( tomcat_max <= streams || streams <= platform_cap )); then
  echo "并发数没有落在平台线程数和 threads.max 之间。" >&2
  exit 1
fi

curl -sf --max-time 3 -X POST "${base}/v1/forward" \
  -H "X-Jasagent-Url: http://127.0.0.1:${up_port}/fast" \
  -H 'Content-Type: text/plain' \
  --data 'ping' >/dev/null

# 等待通行证。目的主机不在白名单里，正文是固定的无效报告，不记录证明文。
curl -sS --max-time 10 -o "${work}/pass.txt" -w '%{http_code}' -X POST "${base}/v1/forward" \
  -H 'X-Jasagent-Url: http://example.com/hook' \
  -H 'X-Jasagent-Ticket: x' \
  -H 'Content-Type: text/plain' \
  --data 'ping' > "${work}/pass.code" &
pass_curl=$!
sleep 1
jcmd "${side_pid}" Thread.print > "${work}/pass.dump"
judge_dump "${work}/pass.dump" > "${work}/pass.judge"
wait "${pass_curl}"
if [[ "$(cat "${work}/pass.code")" != "401" ]]; then
  echo "等待通行证没有按约定拒绝。" >&2
  exit 1
fi

# 等待上游响应头。主机在白名单里，不带证明文。
curl -sS --max-time 10 -o "${work}/hold.txt" -w '%{http_code}' -X POST "${base}/v1/forward" \
  -H "X-Jasagent-Url: http://127.0.0.1:${up_port}/hold" \
  -H 'Content-Type: text/plain' \
  --data 'ping' > "${work}/hold.code" &
hold_curl=$!
sleep 1
jcmd "${side_pid}" Thread.print > "${work}/hold.dump"
judge_dump "${work}/hold.dump" > "${work}/hold.judge"
wait "${hold_curl}"
if [[ "$(cat "${work}/hold.code")" != "200" || "$(cat "${work}/hold.txt")" != "held" ]]; then
  echo "等待上游后没有按约定写回。" >&2
  exit 1
fi

baseline="$(os_tasks "${side_pid}")"
pids=()
for i in $(seq 1 "${streams}"); do
  curl -sN --max-time 20 -o "${work}/stream-${i}.txt" -X POST "${base}/v1/forward" \
    -H "X-Jasagent-Url: http://127.0.0.1:${up_port}/slow" \
    -H 'Content-Type: text/plain' \
    --data 'ping' &
  pids+=("$!")
done
python3 - "${work}" "${streams}" << 'PY'
import pathlib
import sys
import time
root = pathlib.Path(sys.argv[1])
count = int(sys.argv[2])
deadline = time.time() + 2
while time.time() < deadline:
    ready = 0
    for index in range(1, count + 1):
        body = (root / f"stream-{index}.txt").read_bytes() if (root / f"stream-{index}.txt").exists() else b""
        if body.startswith(b"one\n") and b"two\n" not in body:
            ready += 1
    if ready == count:
        sys.exit(0)
    time.sleep(0.05)
print("首块没有在上游结束前到达，或流被整包缓冲。", file=sys.stderr)
for index in range(1, count + 1):
    path = root / f"stream-{index}.txt"
    print(index, path.read_bytes()[:40] if path.exists() else b"", file=sys.stderr)
sys.exit(1)
PY
during="$(os_tasks "${side_pid}")"
jcmd "${side_pid}" Thread.print > "${work}/stream.dump"
judge_dump "${work}/stream.dump" > "${work}/stream.judge"
health_code="$(curl -sS --max-time 2 -o "${work}/health.txt" -w '%{http_code}' "${base}/health")"
fast_code="$(curl -sS --max-time 2 -o "${work}/fast.txt" -w '%{http_code}' -X POST "${base}/v1/forward" \
  -H "X-Jasagent-Url: http://127.0.0.1:${up_port}/fast" \
  -H 'Content-Type: text/plain' \
  --data 'ping')"
if [[ "${health_code}" != "200" || "$(cat "${work}/health.txt")" != *'"status":"up"'* ]]; then
  echo "长流期间 /health 没有在超时内可用。" >&2
  exit 1
fi
if [[ "${fast_code}" != "200" || "$(cat "${work}/fast.txt")" != "ok" ]]; then
  echo "长流期间新的 /v1/forward 没有在超时内可用。" >&2
  exit 1
fi
fail=0
for pid in "${pids[@]}"; do
  if ! wait "${pid}"; then
    fail=1
  fi
done
if (( fail != 0 )); then
  echo "有长流式转发没有完成。" >&2
  exit 1
fi
python3 - "${work}" "${streams}" << 'PY'
import pathlib
import sys
root = pathlib.Path(sys.argv[1])
count = int(sys.argv[2])
for index in range(1, count + 1):
    body = (root / f"stream-{index}.txt").read_bytes()
    if body != b"one\ntwo\n":
        print(f"第 {index} 路没有持续出块。", file=sys.stderr)
        sys.exit(1)
PY
delta=$((during - baseline))
if grep -F 'Pinned:' "${work}/sidecar.log" >/dev/null; then
  echo "虚拟线程在等待或抄写时钉住了载体。" >&2
  exit 1
fi
printf 'ATDD-021 workers=%s tomcatMax=%s streams=%s osDelta=%s pinned=0\n' \
  "${platform_cap}" "${tomcat_max}" "${streams}" "${delta}"
pass "平台线程、流式首块和并发可用性"

# 后面的 018 脚本有自己的进程，这里先收掉本轮的 sidecar。
cleanup
trap - EXIT
side_pid=""
up_pid=""

section "6 复跑 018"
# 018 脚本把对比底写死在 08be62c。其后的需求已经改过网关、控制台和部署清单，原样执行会把那些历史差异算成本轮。
# 这里仍执行同一份检查和模块测试，只把对比底换成 origin/master，用来确认本轮没有再改这些路径。
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
pass "018 第 1 到第 9 条"

section "8 部署清单"
if git diff --name-only origin/master -- deploy/k8s | grep -q .; then
  echo "实现改了 deploy/k8s。" >&2
  exit 1
fi
pass "没有修改 deploy/k8s"

printf '021 TEST PASSED\n'
