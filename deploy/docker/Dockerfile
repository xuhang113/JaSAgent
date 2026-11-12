# Java 21 agent 镜像。构建上下文由 scripts/sandbox/build-images.sh 准备：
# Ubuntu 24.04 base、Temurin 21 JRE，以及 mvn package 产出的 jasagent-app jar。
# 网关、agentpass、示例 RAG 目前还没有独立产物，用同一个 jar，只改 JASAGENT_PORT。
FROM scratch
ADD ubuntu-base.tar.gz /
COPY jre /opt/java
COPY jasagent-app.jar /opt/jasagent/jasagent-app.jar
COPY entrypoint.sh /opt/jasagent/entrypoint.sh
ENV JAVA_HOME=/opt/java \
    PATH=/opt/java/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LANG=C.UTF-8
RUN set -eu \
 && chmod 755 /opt/jasagent/entrypoint.sh \
 && mkdir -p /var/lib/jasagent /tmp \
 && if ! grep -q '^jasagent:' /etc/passwd; then \
      if command -v useradd >/dev/null 2>&1; then \
        useradd --uid 10001 --home-dir /var/lib/jasagent --shell /bin/sh jasagent; \
      else \
        printf '%s\n' 'jasagent:x:10001:10001::/var/lib/jasagent:/bin/sh' >> /etc/passwd; \
        printf '%s\n' 'jasagent:x:10001:' >> /etc/group; \
      fi; \
    fi \
 && chown -R 10001:10001 /var/lib/jasagent \
 && /opt/java/bin/java -version
ARG JASAGENT_ROLE=agent
ARG JASAGENT_PORT=8080
ENV JASAGENT_PORT=${JASAGENT_PORT}
LABEL dev.jasagent.role=${JASAGENT_ROLE}
USER 10001
WORKDIR /var/lib/jasagent
ENTRYPOINT ["/opt/jasagent/entrypoint.sh"]
