# 需求 019 给网关增加公网入口评审记录

- 日期：2026-11-17（Asia/Shanghai）
- 块：网关公网入口（实现改 `jasagent-gateway`；`deploy/k8s` 在 ATDD 之后由部署补 Ingress-nginx 与 Tailscale Funnel）
- 结论：已确认。领队、实现、测试、部署均写「通过」，Hang 于 2026-11-17 16:43（Asia/Shanghai）确认「019开始弄」。确认记录如下。本文件和 `spec.md` 在确认之后写成。018-sidecar-egress 已整块完成后再写本轮规格。

## 候选

调研在 018 期间已在群里交出候选 **019-gateway-public-ingress**：云电脑没有公网 IP（当时记录为内网 `172.30.0.2`），ClusterIP 和端口转发只在这台机器上可用，单靠 NodePort、Ingress 或 Nginx 接不到公网域名流量。控制台只放在 `jasagent-web`，应由现有 `jasagent-gateway:18721` 反向代理，不能把 web 或其它组件直接暴露到公网。当时建议用出站隧道把稳定 HTTPS 主机名接到网关；临时 `trycloudflare.com` 链接不稳定，且对 EventSource 不友好，不能用。

018 完成后，Hang 于 2026-11-16 11:28（Asia/Shanghai）提出按生产形态做：集群由 Kubernetes 支持 Nginx 反向代理，挂到免费域名上，免费资源可以自行创建。领队据此把口径调整为：集群内用 Ingress-nginx 做反向代理，对外挂免费域名，入口仍然只到网关，不直接暴露 web 和其它组件。同时指出约束：这台云机没有公网 IP，单靠 Nginx 和域名接不到公网流量，前面仍需要一条免费出站隧道，建议 Cloudflare Tunnel；路径为免费域名 → 隧道 → Ingress-nginx → `jasagent-gateway`；仍不用临时 trycloudflare 链接。

对照当时的 `master`（018 完成后的结论提交 `0cf0753`）：`jasagent-gateway` 的 Service 是 ClusterIP，端口 18721，没有 Ingress；集群里 `JASAGENT_GATEWAY_WEB_ORIGIN` 指向 `http://jasagent-web.jasagent.svc.cluster.local:18722`，本机默认是 `http://127.0.0.1:18722`。会话 Cookie `JASAGENT_SESSION` 为 HttpOnly、path `/`、SameSite=Lax，没有 Secure。站点根和登录页跳转已经是相对路径（`/admin/`、`/admin/login`）。网关尚未按受信任的转发头识别 HTTPS。前端 `api.js` 使用同源相对路径的 `fetch` 和 `EventSource`。

建议改动：公网 HTTPS 入口只落到 `jasagent-gateway`，不直接暴露 `jasagent-web`、Nacos、MySQL、Redis 和 agent。`JASAGENT_GATEWAY_WEB_ORIGIN` 仍是集群内（或本机）web 上游，不改成公网主机名。网关只信任来自 Ingress/隧道这一跳的 `X-Forwarded-*`，在受信任的 HTTPS 下来源下给会话 Cookie 加 Secure，跳转保持相对路径，不得写死 `127.0.0.1` 或 `http://`。Ingress 和隧道不得整包缓冲，控制台 EventSource 必须能穿过公网入口。Ingress-nginx 不使用 hostNetwork，并纳入 gVisor。官方探测仍须 `PROBE PASSED`，现有 egress、gVisor 范围不缩小。实现只改 `jasagent-gateway`，实现和测试不改 `deploy/k8s`。凭证不进仓库。

候选表决时，调研、实现、测试、部署均写「通过」，并补上上述网关、SSE、探测和凭证约束。调研和部署同时指出：可注册的二级域名通常要付年费，Cloudflare Tunnel 需要把域名 DNS 放到 Cloudflare；若坚持全免费，主机名可用 Tailscale Funnel 的 `*.ts.net`，后面同样经 Ingress-nginx 转到网关。领队按此锁定，请 Hang 确认开工，并在「购买域名接入 Cloudflare」和「全免费使用 `*.ts.net`」之间选定主机名方案。

## 表态

- 领队：通过。锁定路径为稳定主机名 → 出站隧道 → Ingress-nginx → 只转到 `jasagent-gateway`，不暴露 web、Nacos、MySQL、Redis 和 agent。云机没有公网 IP，隧道不能省；不用 trycloudflare。Ingress-nginx 不用 hostNetwork，并纳入 gVisor。SSE 要能穿过入口。官方探测仍须 `PROBE PASSED`，egress/gVisor 不缩小。网关侧 `JASAGENT_GATEWAY_WEB_ORIGIN` 仍是集群内 web 上游；只信任 Ingress/隧道这一跳的 `X-Forwarded-*`；HTTPS 下 Cookie 带 Secure，不写死跳到 `127.0.0.1` 或 HTTP。凭证不进仓库。全员通过后向 Hang 确认开工，并请 Hang 选定主机名方案。
- 调研：通过。同意上述路径和网关/SSE 口径。并指出：可注册的二级域名通常要付年费；若坚持全免费，开工前要定下具体主机名来源，不能只写「免费域名」。
- 实现：通过。同意路径、入口范围和网关改动范围。实现只改 `jasagent-gateway`：`JASAGENT_GATEWAY_WEB_ORIGIN` 不改成公网域名；只信任来自 Ingress-nginx（及其背后隧道）的 `X-Forwarded-*`；按浏览器的 HTTPS 来源设置会话 Cookie 的 Secure 和重定向，不要写死成 `127.0.0.1` 或 HTTP。口令、隧道凭证不进仓库。确认前不改代码。
- 测试：通过。验收按上述路径。仓库侧先验网关转发和 Cookie；公网登录、官方探测仍须 `PROBE PASSED`、egress/gVisor 不缩小，留给部署。实现和测试不改 `deploy/k8s`。没有稳定域名之前，公网登录这一条没法跑完。确认前不写 tdd、不改代码。
- 部署：通过。同意路径和入口范围。隧道不能省；Ingress-nginx 不要用 hostNetwork，并纳入 gVisor；官方探测仍须 `PROBE PASSED`，egress 和 gVisor 范围不缩小。凭证不进 Git。并指出 Cloudflare Tunnel 需要一个 DNS 放在 Cloudflare 上的域名，真正免费的自定义域名不一定能拿到；若不能买域名，可用 Tailscale Funnel 的 `*.ts.net`。确认前不改清单、不部署。
- Hang：于 2026-11-16 11:28 提出按生产形态做，用 Kubernetes 支持 Nginx 反向代理，挂到免费域名，免费资源可以自行创建。于 2026-11-17 16:43（Asia/Shanghai）确认「019开始弄」。领队按 Hang 此前的全免费要求，把主机名锁定为 Tailscale Funnel 的 `*.ts.net`；若要改成购买域名接入 Cloudflare，由 Hang 另行说明。

## 确认后的口径

1. 公网入口路径锁定为：Tailscale Funnel 的稳定主机名 `*.ts.net` → Funnel → Ingress-nginx → 只转到 `jasagent-gateway:18721`。不通过该入口直接暴露 `jasagent-web`、Nacos、MySQL、Redis、agent 或其它非网关组件。
2. 这台云电脑没有公网 IP，单靠 Ingress-nginx 和域名接不到公网流量，Funnel 这一段不能省。不用临时 trycloudflare 链接。本轮不按 Cloudflare Tunnel 实施，除非 Hang 另行确认改走购买域名接入 Cloudflare。
3. `jasagent.gateway.web-origin` / `JASAGENT_GATEWAY_WEB_ORIGIN` 仍指向本机或集群内的 `jasagent-web` 上游，不得改成公网主机名或 `*.ts.net`。
4. 网关只信任来自 Ingress-nginx（及其背后 Funnel）这一跳的 `X-Forwarded-*`，不信任任意客户端自带的这些头。当受信任的这一跳声明请求是 HTTPS 时，会话 Cookie `JASAGENT_SESSION` 必须带 Secure；本机直连 HTTP、没有受信任转发头时，不得强行加 Secure。站点根、登录页等跳转保持相对路径，不得写死 `127.0.0.1` 或 `http://` 绝对地址。
5. Ingress-nginx 与 Funnel 不得整包缓冲，控制台 EventSource（SSE）必须能穿过公网入口。Ingress-nginx 不使用 hostNetwork，并纳入 gVisor。
6. 实现只改 `jasagent-gateway`。`jasagent-web` 本轮不改，前端继续用同源相对路径的 `fetch` 和 `EventSource`。实现和测试阶段不改 `deploy/k8s`。网关仍按单副本。
7. 等本轮 ATDD 全部通过后，由部署在云电脑上补 Ingress-nginx、Tailscale Funnel、入口路由和探测，写 `deploy.md`。须证明经 `https://<*.ts.net>` 可以打开控制台并完成登录，且只打到网关。现有 egress、gVisor 检查范围不得缩小，官方探测整体必须 `PROBE PASSED`。018 已落地的每 Pod sidecar 与出站约定保持有效。验收以部署的云电脑为准，不再依赖 Hang 的 Mac。
8. 本轮改到的模块必须补齐注释、常量、类、方法、测试代码和可执行测试脚本。口令、Funnel/Tailscale 凭证和密钥仍只从仓库外可配置路径或 Secret 读取，不进 Git、日志、异常或响应。变更记录不能代替本轮 ATDD。
9. 界面仍只放在 `jasagent-web`。具体的 `*.ts.net` 主机名由部署创建后写入 `deploy.md`。若创建 Funnel 需要 Hang 的账号或登录，向 Hang 私信办理，不把凭证写进规格或仓库。
