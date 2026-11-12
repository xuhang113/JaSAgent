#!/usr/bin/env bash
# 对已经部署的 jasagent 命名空间做真实探测。失败就退出非零，不把失败写成通过。
# 不打印口令、内部令牌、模型密钥或通行证正文。
set -euo pipefail

NS=jasagent
FAIL=0
TICKET_TOOL="${JASAGENT_TICKET_TOOL:-net.egress}"
TICKET_TARGET="${JASAGENT_TICKET_TARGET:-https://example.com/}"
SIDECAR_URL="${JASAGENT_SIDECAR_URL:-http://jasagent-sidecar.jasagent.svc.cluster.local:8080}"

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

show_addresses() {
  printf 'ADDRESSES\n'
  kubectl -n "${NS}" get svc -o custom-columns='NAME:.metadata.name,CLUSTER-IP:.spec.clusterIP,PORT:.spec.ports[*].port,TYPE:.spec.type'
  printf '%s\n' \
    "gateway=http://jasagent-gateway.jasagent.svc.cluster.local:18721" \
    "agent-0=http://jasagent-agent-0.jasagent.svc.cluster.local:18731" \
    "agent-1=http://jasagent-agent-1.jasagent.svc.cluster.local:18732" \
    "agentpass=http://jasagent-agentpass.jasagent.svc.cluster.local:18741" \
    "sidecar=http://jasagent-sidecar.jasagent.svc.cluster.local:8080" \
    "mysql=jasagent-mysql.jasagent.svc.cluster.local:3306" \
    "redis=jasagent-redis.jasagent.svc.cluster.local:6379"
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
  kubectl -n "${NS}" exec -i jasagent-agent-0 -c agent -- bash -s <<'EOF'
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
}

forward_via_sidecar() {
  local url="$1"
  local expect="$2"
  kubectl -n "${NS}" exec -i jasagent-agent-0 -c agent -- \
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

sidecar_pod() {
  kubectl -n "${NS}" get pod -l app.kubernetes.io/component=sidecar -o jsonpath='{.items[0].metadata.name}'
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
  kubectl -n "${NS}" exec -i jasagent-agent-0 -c agent -- env TARGET="${TICKET_TARGET}" TOOL="${TICKET_TOOL}" bash -s <<'EOF'
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

main() {
  printf 'CLUSTER\n'
  kubectl get nodes -o custom-columns='NAME:.metadata.name,VERSION:.status.nodeInfo.kubeletVersion,RUNTIME:.status.nodeInfo.containerRuntimeVersion,OS:.status.nodeInfo.osImage'
  show_addresses
  run_test pods-ready pods_ready
  run_test runtime-class runtime_classes
  run_test gvisor-agent-0 gvisor_proc jasagent-agent-0 agent
  run_test gvisor-agent-1 gvisor_proc jasagent-agent-1 agent
  run_test gvisor-gateway gvisor_proc "$(gateway_pod)" gateway
  run_test gvisor-agentpass gvisor_proc "$(agentpass_pod)" agentpass
  run_test gvisor-sidecar gvisor_proc "$(sidecar_pod)" sidecar
  run_test gateway-identity gateway_identity
  run_test agentpass-identity agentpass_identity
  run_test gateway-agent-0 gateway_reaches_agent jasagent-agent-0.jasagent.svc.cluster.local 18731
  run_test gateway-agent-1 gateway_reaches_agent jasagent-agent-1.jasagent.svc.cluster.local 18732
  run_test mysql-agent-0 agent_mysql jasagent-agent-0
  run_test mysql-agent-1 agent_mysql jasagent-agent-1
  run_test redis-agent-0 redis_from_agent jasagent-agent-0
  run_test redis-agent-1 redis_from_agent jasagent-agent-1
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
  kubectl -n "${NS}" exec jasagent-agent-0 -c agent -- rm -f /tmp/jasagent-ticket >/dev/null 2>&1 || true
  if [[ "${FAIL}" -ne 0 ]]; then
    printf 'PROBE FAILED\n'
    exit 1
  fi
  printf 'PROBE PASSED\n'
}

main "$@"
