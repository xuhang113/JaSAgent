# 挂在网关 Pod 里，把 127.0.0.1:3306 转到集群 MySQL。构建前先编译 TcpForward.class。
FROM jasagent-agent:0.1.0
USER 0
COPY TcpForward.class /opt/bridge/TcpForward.class
RUN chmod 644 /opt/bridge/TcpForward.class
USER 10001
ENTRYPOINT ["/opt/java/bin/java", "-cp", "/opt/bridge", "TcpForward"]
