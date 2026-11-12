# 约定镜像名 jasagent-sidecar:0.1.0，容器端口 8080。
# 构建上下文是本模块目录，先在仓库根打出可执行 jar：
#   mvn -pl jasagent-sidecar -am -DskipTests package
#   docker build -f jasagent-sidecar/src/main/docker/Dockerfile -t jasagent-sidecar:0.1.0 jasagent-sidecar
# 运行时由 gVisor 的处理器 runsc 拉起：非 root、无额外权限、Java 21、监听 0.0.0.0:8080。
# 密钥、通行证和模型令牌只从运行时环境进入进程，不写进镜像层。
FROM eclipse-temurin:21-jre-jammy

LABEL dev.jasagent.image="jasagent-sidecar:0.1.0" \
      dev.jasagent.runtime="runsc" \
      dev.jasagent.port="8080"

RUN set -eu \
 && if ! id -u jasagent >/dev/null 2>&1; then \
      if command -v useradd >/dev/null 2>&1; then \
        useradd --uid 10001 --home-dir /var/lib/jasagent --shell /usr/sbin/nologin jasagent; \
      else \
        printf '%s\n' 'jasagent:x:10001:10001::/var/lib/jasagent:/usr/sbin/nologin' >> /etc/passwd; \
        printf '%s\n' 'jasagent:x:10001:' >> /etc/group; \
      fi; \
    fi \
 && mkdir -p /opt/jasagent /var/lib/jasagent /tmp \
 && chmod 1777 /tmp \
 && chown -R 10001:10001 /opt/jasagent /var/lib/jasagent

COPY --chown=10001:10001 target/jasagent-sidecar-0.1.0.jar /opt/jasagent/jasagent-sidecar.jar

ENV LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-Djava.security.egd=file:/dev/./urandom -Djava.net.preferIPv4Stack=true -Djava.io.tmpdir=/tmp"

USER 10001
WORKDIR /var/lib/jasagent
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/opt/jasagent/jasagent-sidecar.jar", "--server.address=0.0.0.0", "--server.port=8080"]
