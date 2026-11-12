# 本机没有 eclipse-temurin 时使用。基础镜像是已经构建好的 jasagent-agent，JRE 在 /opt/java。
# 不改 sidecar 的业务代码，只把可执行 jar 放进这层，监听 0.0.0.0:8080。
FROM jasagent-agent:0.1.0
USER 0
COPY jasagent-sidecar-0.1.0.jar /opt/jasagent/jasagent-sidecar.jar
RUN chown 10001:10001 /opt/jasagent/jasagent-sidecar.jar && chmod 644 /opt/jasagent/jasagent-sidecar.jar
ENV JAVA_TOOL_OPTIONS="-Djava.security.egd=file:/dev/./urandom -Djava.net.preferIPv4Stack=true -Djava.io.tmpdir=/tmp"
USER 10001
WORKDIR /var/lib/jasagent
EXPOSE 8080
ENTRYPOINT ["/opt/java/bin/java", "-XX:MaxRAMPercentage=60", "-jar", "/opt/jasagent/jasagent-sidecar.jar", "--server.address=0.0.0.0", "--server.port=8080"]
