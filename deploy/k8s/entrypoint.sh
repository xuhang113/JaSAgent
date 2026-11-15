#!/bin/sh
# 容器里的进程入口。监听地址必须是 0.0.0.0，端口由 JASAGENT_PORT 决定。
# 设置了代理时，集群内部地址不走代理，其余 HTTPS 交给 sidecar。
set -eu
port="${JASAGENT_PORT:-8080}"
proxy_host="${JASAGENT_HTTPS_PROXY_HOST:-}"
proxy_port="${JASAGENT_HTTPS_PROXY_PORT:-}"
non_proxy='localhost|127.0.0.1|*.cluster.local|*.svc|jasagent-mysql|jasagent-redis|jasagent-agentpass|jasagent-gateway|jasagent-web|jasagent-sidecar|jasagent-agent|jasagent-nacos|jasagent-agent-0|jasagent-agent-1'
if [ -n "$proxy_host" ] && [ -n "$proxy_port" ]; then
  exec /opt/java/bin/java \
    --add-opens java.base/jdk.internal.loader=ALL-UNNAMED \
    --add-opens java.base/java.net=ALL-UNNAMED \
    -XX:MaxRAMPercentage=60 \
    -Djava.security.egd=file:/dev/./urandom \
    -Dhttps.proxyHost="$proxy_host" \
    -Dhttps.proxyPort="$proxy_port" \
    -Dhttp.proxyHost="$proxy_host" \
    -Dhttp.proxyPort="$proxy_port" \
    -Dhttp.nonProxyHosts="$non_proxy" \
    -jar /opt/jasagent/jasagent-app.jar \
    --server.address=0.0.0.0 \
    --server.port="${port}" \
    --jasagent.home=/var/lib/jasagent
fi
exec /opt/java/bin/java \
  --add-opens java.base/jdk.internal.loader=ALL-UNNAMED \
  --add-opens java.base/java.net=ALL-UNNAMED \
  -XX:MaxRAMPercentage=60 \
  -Djava.security.egd=file:/dev/./urandom \
  -jar /opt/jasagent/jasagent-app.jar \
  --server.address=0.0.0.0 \
  --server.port="${port}" \
  --jasagent.home=/var/lib/jasagent
