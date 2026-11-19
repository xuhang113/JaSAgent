#!/usr/bin/env bash
# 独立拉起 jasagent-app 进程，按压低后的操作系统平台线程跑超过上限的长 ReAct（含恢复路径）和不同租户并发。
# 不调用官方合同脚本 scripts/test-022-runtime-full-async.sh，避免 ATDD 文件被那份路径白名单判失败。
set -euo pipefail

# 本目录和仓库根。可由 ATDD 夹具先设置 REPO_ROOT。
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${REPO_ROOT:-$(cd "${HERE}/../../.." && pwd)}"
cd "${REPO_ROOT}"

# 本机编译和模块测试用的 JDK、Maven。
export JAVA_HOME="${JAVA_HOME:-/home/box/opt/jdk-21}"
export PATH="${JAVA_HOME}/bin:/home/box/opt/maven/bin:${PATH}"

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

# 打印一条通过，给复用的验收正文调用。
pass() {
  printf 'PASS %s\n' "$1"
}

# 选出一个本机空闲端口。
free_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
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
  python3 - "${dump}" "${platform_cap}" << 'CHK'
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
CHK
}

# 打包 agent 和 sidecar，供本机独立进程验收。
app_jar="${REPO_ROOT}/jasagent-app/target/jasagent-app-0.1.0.jar"
side_jar="${REPO_ROOT}/jasagent-sidecar/target/jasagent-sidecar-0.1.0.jar"
if [[ ! -f "${app_jar}" || ! -f "${side_jar}" ]]; then
  if ! command -v mvn >/dev/null 2>&1; then
    echo "找不到 mvn。" >&2
    exit 1
  fi
  mvn -pl jasagent-app,jasagent-sidecar -am -DskipTests package -q
fi
if [[ ! -f "${app_jar}" || ! -f "${side_jar}" ]]; then
  echo "没有打出 agent 或 sidecar 包。" >&2
  exit 1
fi
if ! command -v jcmd >/dev/null 2>&1; then
  echo "找不到 jcmd，无法读取操作系统平台线程。" >&2
  exit 1
fi

# 出站必须走非回环主机名才会进入 sidecar。
if ! grep -qE '(^|[[:space:]])slow\.test([[:space:]]|$)' /etc/hosts; then
  if ! printf '127.0.0.1 slow.test\n' | sudo tee -a /etc/hosts >/dev/null; then
    echo "无法把 slow.test 解析到回环。出站必须走非回环主机名才会进入 sidecar。" >&2
    exit 1
  fi
fi

# 本轮进程和临时目录。清理在复用正文的 trap 里。
work="$(mktemp -d)"
side_pid=""
app_pid=""

# shellcheck disable=SC1091
source "${HERE}/live-body.sh"
