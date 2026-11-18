# 集群恢复后尝试开启公网 Funnel

- 日期：2026-11-18（Asia/Shanghai）
- 做了什么：云电脑集群状态再次被抹后，按 11-17 的恢复路径重新拉起 Docker、gVisor、k3s 与官方一键栈。本机 Tailscale 已登录为 `jasagent-cloud`（稳定主机名 `jasagent-cloud.tail01f87a.ts.net`）。官方 `configure-funnel.sh` 因该 tailnet 尚未在控制台开启 Serve / Funnel / HTTPS 而无法创建 Funnel；脚本改为限时执行，遇到该提示即以状态码 2 退出并打印控制台链接，不发起 `tailscale up` 或 `login`。
- 涉及模块：scripts/k8s/configure-funnel.sh、requirements/019-gateway-public-ingress/deploy.md
- 怎么验证：官方 `bash scripts/k8s/probe-egress.sh`，015 风格 I-001 至 I-016，本机 Ingress NodePort 30080 登录与 Cookie 检查。公网 `https://jasagent-cloud.tail01f87a.ts.net` 需 Hang 在控制台开启 Serve 后再跑 `bash scripts/k8s/configure-funnel.sh`。详见 `requirements/019-gateway-public-ingress/deploy.md`。
