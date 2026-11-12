#!/bin/sh
# 容器里的进程入口。监听地址必须是 0.0.0.0，端口由 JASAGENT_PORT 决定。
set -eu
port="${JASAGENT_PORT:-8080}"
proxy_host="${JASAGENT_HTTPS_PROXY_HOST:-}"
proxy_port="${JASAGENT_HTTPS_PROXY_PORT:-}"
proxy_args=""
if [ -n "$proxy_host" ] && [ -n "$proxy_port" ]; then
  # 容器里的 127.0.0.1 不是宿主机。HTTPS 走启动脚本传入的代理地址，本机地址不走代理。
  proxy_args="-Dhttps.proxyHost=${proxy_host} -Dhttps.proxyPort=${proxy_port} -Dhttp.proxyHost=${proxy_host} -Dhttp.proxyPort=${proxy_port} -Dhttp.nonProxyHosts=localhost|127.0.0.1|host.docker.internal"
fi
# shellcheck disable=SC2086
exec /opt/java/bin/java \
  --add-opens java.base/jdk.internal.loader=ALL-UNNAMED \
  --add-opens java.base/java.net=ALL-UNNAMED \
  -XX:MaxRAMPercentage=60 \
  -Djava.security.egd=file:/dev/./urandom \
  $proxy_args \
  -jar /opt/jasagent/jasagent-app.jar \
  server \
  --server.address=0.0.0.0 \
  --server.port="${port}" \
  --jasagent.home=/var/lib/jasagent
