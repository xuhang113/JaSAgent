# 基于官方 Ingress-nginx 控制器，去掉文件 capabilities。
# 这台云电脑的 gVisor 无法给带 security.capability 的进程更新凭据（dumb-init / nginx-ingress-controller 带 cap）。
# 控制器监听 8080/8443，不需要 NET_BIND_SERVICE。
# 基础镜像与官方 v1.12.4 摘要保持一致。
FROM registry.k8s.io/ingress-nginx/controller:v1.12.4@sha256:05890cb25d37aa5cfe086614104f798f55e1eeec8dda26d9fd6f6acf0e1554a0
USER 0
# 用普通拷贝覆盖原文件，以丢掉 security.capability，保留属主和权限。
RUN set -eux; \
    for f in /usr/bin/dumb-init /nginx-ingress-controller /usr/local/nginx/sbin/nginx; do \
      if [ -e "$f" ]; then \
        owner="$(stat -c '%u:%g' "$f")"; \
        mode="$(stat -c '%a' "$f")"; \
        tmp="$f.nocap"; \
        cp "$f" "$tmp"; \
        mv "$tmp" "$f"; \
        chown "$owner" "$f"; \
        chmod "$mode" "$f"; \
      fi; \
    done
USER 101
