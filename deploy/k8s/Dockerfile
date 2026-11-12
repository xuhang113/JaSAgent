# 本机 k8s 用的 Java 21 镜像。上下文里要有 ubuntu-base.tar.gz、jre/、jasagent-app.jar、entrypoint.sh。
# 网关和 agentpass 的可执行 jar 也按这个文件名放进来，入口脚本只认这一条路径。
FROM scratch
ADD ubuntu-base.tar.gz /
COPY jre /opt/java
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
COPY jasagent-app.jar /opt/jasagent/jasagent-app.jar
ENV JASAGENT_PORT=${JASAGENT_PORT}
LABEL dev.jasagent.role=${JASAGENT_ROLE}
USER 10001
WORKDIR /var/lib/jasagent
ENTRYPOINT ["/opt/jasagent/entrypoint.sh"]
