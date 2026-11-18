# 集群恢复后开启公网 Funnel

- 日期：2026-11-18（Asia/Shanghai）
- 做了什么：云电脑集群状态再次被抹后，按 11-17 的恢复路径重新拉起 Docker、gVisor、k3s 与官方一键栈。本机 Tailscale 已登录为 `jasagent-cloud`（稳定主机名 `jasagent-cloud.tail01f87a.ts.net`）。Hang 在控制台开启 Serve / Funnel / HTTPS 后，将 Funnel 指到 Ingress-nginx NodePort `http://127.0.0.1:30080`。`configure-funnel.sh` 改为直接执行 `tailscale funnel --bg http://127.0.0.1:30080`：若尚未开启 Serve，仍限时退出并打印控制台链接；已开启时不再先 `serve` 再 `funnel --bg 443`，以免把后端改成本机 443。公网 `https://jasagent-cloud.tail01f87a.ts.net` 的 `/health` 与登录页已通，证书由 Let's Encrypt 签发。不发起 `tailscale up` 或 `login`，凭证不进仓库。
- 涉及模块：scripts/k8s/configure-funnel.sh、requirements/019-gateway-public-ingress/deploy.md
- 怎么验证：官方 `bash scripts/k8s/probe-egress.sh`（08:39:39 Asia/Shanghai，`PROBE PASSED`），015 风格 I-001 至 I-016（08:41:42，`016 IT PASSED`），本机 Ingress NodePort 30080 登录与 Cookie 检查，以及经公网 Funnel 边缘访问 `https://jasagent-cloud.tail01f87a.ts.net/health`、`/admin/login` 和未登录跳转。集群外（含手机）登录与 EventSource 仍待确认。详见 `requirements/019-gateway-public-ingress/deploy.md`。
