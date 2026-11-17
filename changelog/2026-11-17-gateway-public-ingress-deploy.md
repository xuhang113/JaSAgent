# 部署阶段为网关补公网入口

- 日期：2026-11-17（Asia/Shanghai）
- 做了什么：在 `deploy/k8s` 增加 Ingress-nginx（不使用 hostNetwork，纳入 gVisor）和只转到 `jasagent-gateway:18721` 的 Ingress。网关通过 ConfigMap 写入 `JASAGENT_GATEWAY_FORWARDED_TRUSTED`，只信任 Ingress 这一跳，禁止 `0.0.0.0/0` 与 `::/0`。`JASAGENT_GATEWAY_WEB_ORIGIN` 仍是集群内 web。一键脚本在业务就绪后安装入口并刷新受信任名单。官方探测增加 Ingress 相关检查，不缩小既有 egress 与 gVisor 范围。Funnel 配置脚本只在本机 Tailscale 已登录时启用，不发起交互登录。
- 涉及模块：deploy/k8s、scripts/up-stack.sh、scripts/k8s/probe-egress.sh、scripts/k8s/configure-funnel.sh
- 怎么验证：`bash scripts/k8s/prepare-cluster.sh`（若集群已就绪会跳过），`bash scripts/up-stack.sh`，官方 `bash scripts/k8s/probe-egress.sh`。公网主机名与 Funnel 结果见 `requirements/019-gateway-public-ingress/deploy.md`。
