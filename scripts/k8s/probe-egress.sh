#!/usr/bin/env bash
# 对已经部署的 jasagent 命名空间做真实探测。失败就退出非零，不把失败写成通过。
# 不打印口令、内部令牌、模型密钥或通行证正文。
# agent 相关项按当时从 Nacos 发现到的实例验收，不再写死 jasagent-agent-0 / jasagent-agent-1。
# /v1/forward 探测走发现到的 agent Pod 本机回环 sidecar（127.0.0.1:18761），不打已删除的 sidecar Service。
# gvisor-sidecar 检查的是 agent Pod 里的 sidecar 容器。现有的 egress 与 gVisor 检查范围不缩小。另增 Ingress-nginx（无 hostNetwork、gVisor、只转到网关）检查，不依赖 Funnel 是否已登录。
set -euo pipefail

NS=jasagent
FAIL=0
TICKET_TOOL="${JASAGENT_TICKET_TOOL:-net.egress}"
TICKET_TARGET="${JASAGENT_TICKET_TARGET:-https://example.com/}"
SIDECAR_URL="${JASAGENT_SIDECAR_URL:-http://127.0.0.1:18761}"
# 发现结果缓存：每行 "ip port pod"
DISCOVERED_FILE=""

pass() {
  printf 'PASS %s\n' "$1"
}

fail() {
  printf 'FAIL %s\n' "$1"
  FAIL=1
}

run_test() {
  local name="$1"
  shift
  printf 'TEST %s\n' "${name}"
  if "$@"; then
    pass "${name}"
  else
    fail "${name}"
  fi
}

pods_ready() {
  local bad=0
  local line ready running
  kubectl -n "${NS}" get pods --no-headers
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    ready="$(printf '%s\n' "${line}" | awk '{print $2}')"
    running="$(printf '%s\n' "${line}" | awk '{print $3}')"
    if [[ "${running}" != "Running" || "${ready%%/*}" != "${ready##*/}" ]]; then
      bad=1
    fi
  done < <(kubectl -n "${NS}" get pods --no-headers)
  [[ "${bad}" -eq 0 ]]
}

nacos_addr() {
  local ip
  ip="$(kubectl -n "${NS}" get svc jasagent-nacos -o jsonpath='{.spec.clusterIP}')"
  if [[ -z "${ip}" || "${ip}" == "None" ]]; then
    return 1
  fi
  printf '%s\n' "${ip}"
}

# 从 Nacos 查出健康实例，并对应到当前 agent Pod。结果写入 DISCOVERED_FILE。
# 注册地址若是回环，或健康实例少于 2 台，都算失败。
load_discovered() {
  local nacos url tmp map pod ip port found
  nacos="$(nacos_addr)" || {
    printf 'nacos-service-missing\n'
    return 1
  }
  url="http://${nacos}:8848/nacos/v1/ns/instance/list?serviceName=jasagent-agent&healthyOnly=true"
  tmp="$(mktemp)"
  if ! python3 - "${url}" "${tmp}" <<'PY'
import json, sys, urllib.request

url, dest = sys.argv[1], sys.argv[2]
try:
    with urllib.request.urlopen(url, timeout=10) as resp:
        data = json.load(resp)
except Exception as exc:
    sys.stderr.write("nacos-query-failed\n")
    sys.exit(1)
hosts = data.get("hosts") or []
rows = []
for host in hosts:
    if not host.get("healthy", False) or not host.get("enabled", True):
        continue
    ip = str(host.get("ip") or "").strip()
    port = host.get("port")
    if not ip or port is None:
        continue
    if ip in {"127.0.0.1", "localhost", "::1", "localhost.localdomain"} or ip.startswith("127."):
        sys.stderr.write("loopback-registered\n")
        sys.exit(2)
    rows.append(f"{ip} {port}")
if len(rows) < 2:
    sys.stderr.write("too-few-instances\n")
    sys.exit(3)
with open(dest, "w", encoding="utf-8") as fh:
    fh.write("\n".join(rows) + "\n")
PY
  then
    rm -f "${tmp}"
    return 1
  fi
  map="$(mktemp)"
  kubectl -n "${NS}" get pod -l app.kubernetes.io/component=agent -o jsonpath='{range .items[*]}{.status.podIP}{" "}{.metadata.name}{"\n"}{end}' > "${map}"
  : > "${DISCOVERED_FILE}"
  while read -r ip port; do
    [[ -n "${ip}" ]] || continue
    found=""
    while read -r pip pod; do
      if [[ "${pip}" == "${ip}" ]]; then
        found="${pod}"
        break
      fi
    done < "${map}"
    if [[ -z "${found}" ]]; then
      printf 'unmapped-instance %s:%s\n' "${ip}" "${port}"
      rm -f "${tmp}" "${map}"
      return 1
    fi
    printf '%s %s %s\n' "${ip}" "${port}" "${found}" >> "${DISCOVERED_FILE}"
  done < "${tmp}"
  rm -f "${tmp}" "${map}"
  if [[ "$(wc -l < "${DISCOVERED_FILE}" | tr -d ' ')" -lt 2 ]]; then
    printf 'too-few-mapped-instances\n'
    return 1
  fi
  printf 'DISCOVERED\n'
  cat "${DISCOVERED_FILE}"
}

first_agent_pod() {
  awk 'NR==1 { print $3; exit }' "${DISCOVERED_FILE}"
}

show_addresses() {
  printf 'ADDRESSES\n'
  kubectl -n "${NS}" get svc -o custom-columns='NAME:.metadata.name,CLUSTER-IP:.spec.clusterIP,PORT:.spec.ports[*].port,TYPE:.spec.type'
  printf '%s\n' \
    "gateway=http://jasagent-gateway.jasagent.svc.cluster.local:18721" \
    "nacos=http://jasagent-nacos.jasagent.svc.cluster.local:8848" \
    "agentpass=http://jasagent-agentpass.jasagent.svc.cluster.local:18741" \
    "sidecar=http://127.0.0.1:18761 (agent pod loopback)" \
    "mysql=jasagent-mysql.jasagent.svc.cluster.local:3306" \
    "redis=jasagent-redis.jasagent.svc.cluster.local:6379"
  if [[ -f "${DISCOVERED_FILE}" ]]; then
    while read -r ip port pod; do
      printf 'discovered-agent=%s %s:%s\n' "${pod}" "${ip}" "${port}"
      printf 'sidecar-in-pod=%s http://127.0.0.1:18761\n' "${pod}"
    done < "${DISCOVERED_FILE}"
  fi
}

gvisor_proc() {
  local pod="$1"
  local container="$2"
  local version
  version="$(kubectl -n "${NS}" exec "${pod}" -c "${container}" -- cat /proc/version)"
  printf '%s %s %s\n' "${pod}" "${container}" "${version}"
  case "${version}" in
    *gvisor*|*gVisor*) return 0 ;;
    *) return 1 ;;
  esac
}

runtime_classes() {
  kubectl -n "${NS}" get pods -o custom-columns='NAME:.metadata.name,RUNTIME:.spec.runtimeClassName,READY:.status.containerStatuses[*].ready'
  local name runtime
  while IFS= read -r name; do
    [[ -n "${name}" ]] || continue
    runtime="$(kubectl -n "${NS}" get pod "${name}" -o jsonpath='{.spec.runtimeClassName}')"
    case "${name}" in
      jasagent-mysql*|jasagent-redis*)
        if [[ -n "${runtime}" && "${runtime}" != "runc" ]]; then
          printf 'middleware runtime %s=%s\n' "${name}" "${runtime}"
          return 1
        fi
        ;;
      *)
        if [[ "${runtime}" != "gvisor" ]]; then
          printf 'business runtime %s=%s\n' "${name}" "${runtime}"
          return 1
        fi
        ;;
    esac
  done < <(kubectl -n "${NS}" get pods -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
}

http_get() {
  local pod="$1"
  local container="$2"
  local host="$3"
  local port="$4"
  local path="$5"
  kubectl -n "${NS}" exec -i "${pod}" -c "${container}" -- env HOST="${host}" PORT="${port}" PATH_REQ="${path}" bash -s <<'EOF'
set -eu
exec 3<>"/dev/tcp/${HOST}/${PORT}"
printf 'GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n' "${PATH_REQ}" "${HOST}" >&3
timeout 15 head -c 700 <&3
EOF
}

gateway_reaches_agent() {
  local host="$1"
  local port="$2"
  local body
  body="$(http_get "$(gateway_pod)" gateway "${host}" "${port}" /health)"
  printf '%s\n' "${body}" | head -n 20
  printf '%s\n' "${body}" | grep -q 'HTTP/1.1 200' && printf '%s\n' "${body}" | grep -q '"storage":"up"'
}

agent_mysql() {
  local pod="$1"
  local body
  body="$(http_get "${pod}" agent 127.0.0.1 8080 /health)"
  printf '%s\n' "${body}" | head -n 20
  printf '%s\n' "${body}" | grep -q 'HTTP/1.1 200' && printf '%s\n' "${body}" | grep -q '"storage":"up"'
}

gateway_identity() {
  local body
  body="$(http_get "$(gateway_pod)" gateway 127.0.0.1 18721 /health)"
  printf '%s\n' "${body}" | head -n 20
  printf '%s\n' "${body}" | grep -q 'HTTP/1.1 200' \
    && printf '%s\n' "${body}" | grep -q '"status":"up"' \
    && ! printf '%s\n' "${body}" | grep -q '"storage"'
}

agentpass_identity() {
  local body
  body="$(http_get "$(agentpass_pod)" agentpass 127.0.0.1 18741 /health)"
  printf '%s\n' "${body}" | head -n 20
  printf '%s\n' "${body}" | grep -q 'HTTP/1.1 200' && printf '%s\n' "${body}" | grep -q '"service":"agentpass"'
}

redis_from_agent() {
  local pod="$1"
  local line
  line="$(kubectl -n "${NS}" exec "${pod}" -c agent -- bash -c 'exec 3<>/dev/tcp/jasagent-redis.jasagent.svc.cluster.local/6379; printf "PING\r\n" >&3; IFS= read -r -t 5 line <&3; printf "%s\n" "$line"')"
  printf '%s\n' "${line}"
  [[ "${line}" == +PONG* ]]
}

direct_external_blocked() {
  local pod
  pod="$(first_agent_pod)"
  kubectl -n "${NS}" exec -i "${pod}" -c agent -- bash -s <<'EOF'
set -eu
ip="$(getent ahostsv4 example.com | awk '{print $1; exit}')"
if [ -z "${ip}" ]; then
  echo "no-ipv4"
  exit 2
fi
echo "resolved=${ip}"
if timeout 5 bash -c "echo >/dev/tcp/${ip}/443"; then
  echo "direct-open"
  exit 1
fi
echo "direct-blocked"
exit 0
EOF
  # 额外观察同 Pod sidecar 是否还能出站，避免本机兜底仍按 Pod IP 误伤 sidecar。
  # 本项是否通过仍只看 agent 进程被拦住。
  kubectl -n "${NS}" exec -i "${pod}" -c sidecar -- bash -s <<'EOF' || true
set -eu
ip="$(getent ahostsv4 example.com | awk '{print $1; exit}')"
if [ -z "${ip}" ]; then
  echo "sidecar-no-ipv4"
  exit 0
fi
echo "sidecar-resolved=${ip}"
if timeout 5 bash -c "echo >/dev/tcp/${ip}/443"; then
  echo "sidecar-open"
  exit 0
fi
echo "sidecar-blocked"
exit 0
EOF
}

forward_via_sidecar() {
  local url="$1"
  local expect="$2"
  local pod
  pod="$(first_agent_pod)"
  kubectl -n "${NS}" exec -i "${pod}" -c agent -- \
    env URL="${url}" EXPECT="${expect}" TOOL="${TICKET_TOOL}" SIDECAR="${SIDECAR_URL}" bash -s <<'EOF'
set -eu
hostport="${SIDECAR#http://}"
hostport="${hostport#https://}"
host="${hostport%%:*}"
port="${hostport##*:}"
port="${port%%/*}"
exec 3<>"/dev/tcp/${host}/${port}"
{
  printf 'GET /v1/forward HTTP/1.1\r\n'
  printf 'Host: %s\r\n' "${host}"
  printf 'X-Jasagent-Url: %s\r\n' "${URL}"
  if [ "${EXPECT}" = "forwarded-ticket" ]; then
    ticket="$(cat /tmp/jasagent-ticket)"
    printf 'X-Jasagent-Ticket: %s\r\n' "${ticket}"
    printf 'X-Jasagent-Tenant: local\r\n'
    printf 'X-Jasagent-User: stack\r\n'
    printf 'X-Jasagent-Session: s1\r\n'
    printf 'X-Jasagent-Execution: e1\r\n'
    printf 'X-Jasagent-Tool: %s\r\n' "${TOOL}"
  fi
  printf 'Connection: close\r\n\r\n'
} >&3
raw="$(timeout 75 head -c 2500 <&3 || true)"
exec 3>&-
status="$(printf '%s\n' "${raw}" | head -n 1 | tr -d '\r')"
printf 'status=%s\n' "${status}"
reason="$(printf '%s' "${raw}" | tr -d '\r' | grep -o '"reason":"[^"]*"' | head -n 1 || true)"
if [ -n "${reason}" ]; then
  printf '%s\n' "${reason}"
fi
case "${EXPECT}" in
  denied)
    printf '%s' "${raw}" | grep -q '"reason":"missing-ticket"'
    ;;
  forwarded|forwarded-ticket)
    if printf '%s' "${raw}" | grep -q '"admitted":false'; then
      exit 1
    fi
    printf '%s\n' "${status}" | grep -Eq '^HTTP/1\.[01] [0-9]+'
    ;;
  *)
    exit 2
    ;;
esac
EOF
}

agentpass_pod() {
  kubectl -n "${NS}" get pod -l app.kubernetes.io/component=agentpass -o jsonpath='{.items[0].metadata.name}'
}

gateway_pod() {
  kubectl -n "${NS}" get pod -l app.kubernetes.io/component=gateway -o jsonpath='{.items[0].metadata.name}'
}

# sidecar 已放入 agent Pod，不再有独立的 component=sidecar 工作负载。
# 检查发现到的 agent Pod 里的 sidecar 容器。
sidecar_pod() {
  first_agent_pod
}

nacos_pod() {
  kubectl -n "${NS}" get pod -l app.kubernetes.io/component=nacos -o jsonpath='{.items[0].metadata.name}'
}

prepare_pass_user() {
  local pod
  pod="$(agentpass_pod)"
  kubectl -n "${NS}" exec -i "${pod}" -c agentpass -- env TOOL="${TICKET_TOOL}" bash -s <<'EOF'
set -eu
umask 077
mkdir -p /var/lib/jasagent/stack/jasagent/auth/sessions
printf '%s\n' 'login=stack' > /var/lib/jasagent/stack/jasagent/auth/sessions/s1
cat > /var/lib/jasagent/stack/jasagent/auth/policies.yaml <<POLICY
rules:
- tool: ${TOOL}
  decision: ALLOW
POLICY
EOF
}

issue_ticket() {
  local pod
  pod="$(first_agent_pod)"
  kubectl -n "${NS}" exec -i "${pod}" -c agent -- env TARGET="${TICKET_TARGET}" TOOL="${TICKET_TOOL}" bash -s <<'EOF'
set -eu
body="$(printf '{"tenantId":"local","userId":"stack","sessionId":"s1","executionId":"e1","target":"%s","toolName":"%s"}' "${TARGET}" "${TOOL}")"
exec 3<>/dev/tcp/jasagent-agentpass.jasagent.svc.cluster.local/18741
printf 'POST /v1/tickets HTTP/1.1\r\nHost: jasagent-agentpass\r\nContent-Type: application/json\r\nContent-Length: %s\r\nConnection: close\r\n\r\n%s' "${#body}" "${body}" >&3
resp="$(timeout 15 head -c 4000 <&3 || true)"
status="$(printf '%s\n' "${resp}" | head -n 1)"
printf '%s\n' "${status}"
ticket="$(printf '%s' "${resp}" | sed -n 's/.*"ticket":"\([^"]*\)".*/\1/p' | head -n 1)"
if [ -z "${ticket}" ]; then
  exit 1
fi
umask 077
printf '%s' "${ticket}" > /tmp/jasagent-ticket
chmod 600 /tmp/jasagent-ticket
EOF
}

gvisor_discovered_agents() {
  local ip port pod bad=0
  while read -r ip port pod; do
    if ! gvisor_proc "${pod}" agent; then
      bad=1
    fi
  done < "${DISCOVERED_FILE}"
  [[ "${bad}" -eq 0 ]]
}

gateway_reaches_discovered() {
  local ip port pod bad=0
  while read -r ip port pod; do
    printf 'instance %s %s:%s\n' "${pod}" "${ip}" "${port}"
    if ! gateway_reaches_agent "${ip}" "${port}"; then
      bad=1
    fi
  done < "${DISCOVERED_FILE}"
  [[ "${bad}" -eq 0 ]]
}

mysql_discovered() {
  local ip port pod bad=0
  while read -r ip port pod; do
    printf 'instance %s\n' "${pod}"
    if ! agent_mysql "${pod}"; then
      bad=1
    fi
  done < "${DISCOVERED_FILE}"
  [[ "${bad}" -eq 0 ]]
}

redis_discovered() {
  local ip port pod bad=0
  while read -r ip port pod; do
    printf 'instance %s\n' "${pod}"
    if ! redis_from_agent "${pod}"; then
      bad=1
    fi
  done < "${DISCOVERED_FILE}"
  [[ "${bad}" -eq 0 ]]
}


# Ingress-nginx 控制器 Pod。没有时输出空字符串。
ingress_controller_pod() {
  kubectl -n ingress-nginx get pods -l app.kubernetes.io/name=ingress-nginx,app.kubernetes.io/component=controller -o jsonpath='{.items[0].metadata.name}'
}

# 控制器不得使用 hostNetwork，必须纳入 gVisor。
ingress_nginx_runtime() {
  local pod runtime hostnet
  pod="$(ingress_controller_pod)"
  if [[ -z "${pod}" ]]; then
    printf 'ingress-nginx-missing\n'
    return 1
  fi
  runtime="$(kubectl -n ingress-nginx get pod "${pod}" -o jsonpath='{.spec.runtimeClassName}')"
  hostnet="$(kubectl -n ingress-nginx get pod "${pod}" -o jsonpath='{.spec.hostNetwork}')"
  printf 'ingress-nginx %s runtime=%s hostNetwork=%s\n' "${pod}" "${runtime}" "${hostnet:-false}"
  if [[ "${hostnet}" == "true" ]]; then
    return 1
  fi
  if [[ "${runtime}" != "gvisor" ]]; then
    return 1
  fi
  gvisor_proc_ns ingress-nginx "${pod}" controller
}

# 与 gvisor_proc 相同，但命名空间可指定。
gvisor_proc_ns() {
  local ns="$1"
  local pod="$2"
  local container="$3"
  local ver
  if [[ -z "${pod}" ]]; then
    return 1
  fi
  ver="$(kubectl -n "${ns}" exec "${pod}" -c "${container}" -- cat /proc/version 2>/dev/null || true)"
  printf '%s %s %s %s\n' "${ns}" "${pod}" "${container}" "${ver}"
  case "${ver}" in
    *gvisor*|*gVisor*) return 0 ;;
    *) return 1 ;;
  esac
}

# 公网 Ingress 只能转到 jasagent-gateway:18721。
ingress_only_gateway() {
  local bad=0
  local out
  if ! kubectl -n "${NS}" get ingress jasagent-gateway >/dev/null 2>&1; then
    printf 'ingress-missing\n'
    return 1
  fi
  out="$(kubectl -n "${NS}" get ingress jasagent-gateway -o jsonpath='{range .spec.rules[*].http.paths[*]}{.backend.service.name}:{.backend.service.port.number}{"\n"}{end}')"
  printf '%s\n' "${out}"
  if [[ -z "${out}" ]]; then
    return 1
  fi
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    if [[ "${line}" != "jasagent-gateway:18721" ]]; then
      printf 'unexpected-backend %s\n' "${line}"
      bad=1
    fi
  done <<< "${out}"
  [[ "${bad}" -eq 0 ]]
}

# 受信任转发名单必须存在，且不是全网信任。
forwarded_trusted_ingress_only() {
  local trusted
  trusted="$(kubectl -n "${NS}" get configmap jasagent-gateway-forwarded -o jsonpath='{.data.trusted}' 2>/dev/null || true)"
  printf 'trusted=%s\n' "${trusted}"
  if [[ -z "${trusted}" ]]; then
    return 1
  fi
  case ",${trusted}," in
    *,0.0.0.0,*|*,0.0.0.0/0,*|*,::,*|*,::/0,*) return 1 ;;
  esac
  if printf '%s\n' "${trusted}" | grep -E '(^|,)[^,/]+/0(,|$)' >/dev/null; then
    return 1
  fi
  # 只接受明确的这一跳地址或小网段，不能是全网或整个集群默认 Pod CIDR。
  local item prefix
  IFS=',' read -r -a parts <<< "${trusted}"
  for item in "${parts[@]}"; do
    item="$(printf '%s' "${item}" | awk '{$1=$1; print}')"
    [[ -n "${item}" ]] || continue
    prefix="${item##*/}"
    if [[ "${item}" == *:* ]]; then
      if [[ "${item}" != */* || "${prefix}" -lt 64 ]]; then
        printf 'trusted-too-broad %s\n' "${item}"
        return 1
      fi
    else
      if [[ "${item}" != */* || "${prefix}" -lt 24 ]]; then
        printf 'trusted-too-broad %s\n' "${item}"
        return 1
      fi
    fi
  done
  kubectl -n ingress-nginx get pods -l app.kubernetes.io/name=ingress-nginx,app.kubernetes.io/component=controller -o jsonpath='{range .items[*]}{.status.podIP}{"\n"}{end}'
  return 0
}

# 网关仍是单副本，web 上游仍是集群内地址。
gateway_single_replica_and_web_origin() {
  local replicas ready origin
  replicas="$(kubectl -n "${NS}" get deploy jasagent-gateway -o jsonpath='{.spec.replicas}')"
  ready="$(kubectl -n "${NS}" get deploy jasagent-gateway -o jsonpath='{.status.readyReplicas}')"
  origin="$(kubectl -n "${NS}" get deploy jasagent-gateway -o jsonpath='{range .spec.template.spec.containers[?(@.name=="gateway")].env[*]}{.name}={.value}{"\n"}{end}' | awk -F= '$1=="JASAGENT_GATEWAY_WEB_ORIGIN"{print $2}')"
  printf 'replicas=%s ready=%s origin=%s\n' "${replicas}" "${ready}" "${origin}"
  [[ "${replicas}" == "1" && "${ready}" == "1" && "${origin}" == "http://jasagent-web.jasagent.svc.cluster.local:18722" ]]
}

main() {
  DISCOVERED_FILE="$(mktemp)"
  trap 'rm -f "${DISCOVERED_FILE}"' EXIT
  printf 'CLUSTER\n'
  kubectl get nodes -o custom-columns='NAME:.metadata.name,VERSION:.status.nodeInfo.kubeletVersion,RUNTIME:.status.nodeInfo.containerRuntimeVersion,OS:.status.nodeInfo.osImage'
  if ! load_discovered; then
    printf 'FAIL discovered-instances\n'
    FAIL=1
  else
    printf 'PASS discovered-instances\n'
  fi
  show_addresses
  run_test pods-ready pods_ready
  run_test runtime-class runtime_classes
  run_test gvisor-discovered-agents gvisor_discovered_agents
  run_test gvisor-gateway gvisor_proc "$(gateway_pod)" gateway
  run_test gvisor-agentpass gvisor_proc "$(agentpass_pod)" agentpass
  run_test gvisor-sidecar gvisor_proc "$(sidecar_pod)" sidecar
  run_test gvisor-nacos gvisor_proc "$(nacos_pod)" nacos
  run_test gateway-identity gateway_identity
  run_test agentpass-identity agentpass_identity
  run_test gateway-discovered-agents gateway_reaches_discovered
  run_test mysql-discovered-agents mysql_discovered
  run_test redis-discovered-agents redis_discovered
  run_test agent-direct-external-blocked direct_external_blocked
  run_test llm-deepseek-no-ticket forward_via_sidecar https://api.deepseek.com/ forwarded
  run_test llm-xai-no-ticket forward_via_sidecar https://api.x.ai/ forwarded
  run_test external-no-ticket-denied forward_via_sidecar "${TICKET_TARGET}" denied
  if prepare_pass_user && issue_ticket; then
    pass ticket-issued
  else
    fail ticket-issued
  fi
  run_test external-with-ticket forward_via_sidecar "${TICKET_TARGET}" forwarded-ticket
  kubectl -n "${NS}" exec "$(first_agent_pod)" -c agent -- rm -f /tmp/jasagent-ticket >/dev/null 2>&1 || true
  run_test ingress-nginx-runtime ingress_nginx_runtime
  run_test ingress-only-gateway ingress_only_gateway
  run_test forwarded-trusted-ingress-only forwarded_trusted_ingress_only
  run_test gateway-single-replica-web-origin gateway_single_replica_and_web_origin
  if [[ "${FAIL}" -ne 0 ]]; then
    printf 'PROBE FAILED\n'
    exit 1
  fi
  printf 'PROBE PASSED\n'
}

main "$@"
