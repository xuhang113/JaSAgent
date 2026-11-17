# 需求 019 给网关增加公网入口

**状态**: 已确认

确认记录见同目录 `review.md`。2026-11-17（Asia/Shanghai）领队、实现、测试、部署均已通过。Hang 于 16:43 确认「019开始弄」。018-sidecar-egress 已整块完成。主机名方案锁定为 Tailscale Funnel 的 `*.ts.net`。

## 这次要改什么

018 已经把离开集群的 HTTP 收进每 Pod sidecar，官方探测为 `PROBE PASSED`。界面仍只放在 `jasagent-web`，由 `jasagent-gateway` 反向代理。现在的问题是：部署所用云电脑没有公网 IP，网关 Service 是 ClusterIP，没有 Ingress，手机或外部浏览器打不开控制台。会话 Cookie `JASAGENT_SESSION` 没有 Secure，网关也不按受信任的转发头识别 HTTPS。单靠 Ingress-nginx 和域名仍然接不到公网流量。

这次改成这几件事。

1. 用 Tailscale Funnel 提供稳定的 `*.ts.net` HTTPS 主机名，经 Ingress-nginx 只转到 `jasagent-gateway:18721`，不直接暴露 `jasagent-web`、Nacos、MySQL、Redis 和 agent。
2. `JASAGENT_GATEWAY_WEB_ORIGIN` 仍指向集群内（或本机）web 上游，不改成公网主机名。
3. 网关只信任来自 Ingress/Funnel 这一跳的 `X-Forwarded-*`；在受信任的 HTTPS 下来源下给会话 Cookie 加 Secure；跳转保持相对路径，不得写死 `127.0.0.1` 或 `http://`。
4. 实现只改 `jasagent-gateway`，实现和测试阶段不改 `deploy/k8s`。等 ATDD 全部通过后，由部署在云电脑上补 Ingress-nginx、Funnel、入口路由和探测，并写 `deploy.md`。

网关仍按单副本。界面仍只放在 `jasagent-web`。验收和部署都以部署的云电脑为准，不再依赖 Hang 的 Mac。本轮不按 Cloudflare Tunnel 实施，除非 Hang 另行确认。不用临时 trycloudflare 链接。

## 模块

| 模块 | 现在负责什么 | 这次改什么 |
| --- | --- | --- |
| jasagent-gateway | 唯一对外入口（本机默认 `127.0.0.1:18721`，集群里 Service 为 ClusterIP 18721）；反向代理控制台到 `jasagent.gateway.web-origin`；登录、会话、`/api/me`、`/health` 在网关；会话 Cookie 为 HttpOnly、path `/`、SameSite=Lax，没有 Secure；跳转已是相对路径；不按受信任转发头识别 HTTPS | 保持 web 上游配置仍指向本机或集群内 `jasagent-web`，不得改成公网主机名。只信任来自 Ingress-nginx（及其背后 Funnel）这一跳的 `X-Forwarded-*`。当受信任的这一跳声明请求是 HTTPS 时，会话 Cookie 必须带 Secure；本机直连 HTTP 时不得强行加 Secure。跳转继续用相对路径，不得写死 `127.0.0.1` 或 `http://` |
| jasagent-web | 只提供页面，由网关反向代理；前端用同源相对路径的 `fetch` 和 `EventSource` | 本轮不改 |
| deploy/k8s | 没有 Ingress，没有 Funnel；网关 Service 为 ClusterIP；业务容器使用 gVisor | 实现和测试阶段不动。ATDD 全部通过后，由部署在云电脑上增加 Ingress-nginx（不使用 hostNetwork，并纳入 gVisor）和 Tailscale Funnel，把 `*.ts.net` 只转到 `jasagent-gateway:18721`，保证 SSE 能穿过入口，官方探测仍须 `PROBE PASSED` |

## 改动地方

**jasagent-gateway**

`jasagent.gateway.web-origin` 和集群里的 `JASAGENT_GATEWAY_WEB_ORIGIN` 仍指向本机或集群内的 `jasagent-web`（当前集群值为 `http://jasagent-web.jasagent.svc.cluster.local:18722`），不得改成 Funnel 主机名或其它公网地址。

网关必须能识别经 Funnel 和 Ingress-nginx 进来的 HTTPS，但只信任这一跳补上的 `X-Forwarded-Proto`、`X-Forwarded-For` 等头，不信任任意客户端自己带的这些头。当受信任的这一跳声明请求是 HTTPS 时，签发和清除 `JASAGENT_SESSION` 都必须带 Secure，并继续保持 HttpOnly、path `/`、SameSite=Lax。本机或集群内以明文 HTTP 直连网关、没有受信任转发头时，不得强行加 Secure，否则浏览器不会带上 Cookie。

站点根转到 `/admin/`、登录页转到 `/admin/login` 以及其它登录后跳转，必须继续使用相对路径，不得写成 `http://127.0.0.1:...` 或其它写死的 HTTP 绝对地址。网关仍按单副本。本轮改到的类、常量、方法都要有注释。口令、会话令牌和密钥不写进仓库、日志、异常或响应。

**jasagent-web**

本轮不改。前端继续用同源相对路径访问接口和 EventSource，不把公网主机名写进前端配置。

**deploy/k8s**

实现和测试提交不得修改这个目录。等本轮 ATDD 全部通过后，由部署在云电脑上补这些内容。

安装并配置 Ingress-nginx，不使用 hostNetwork，并纳入 gVisor。用 Tailscale Funnel 提供稳定的 `*.ts.net` HTTPS 主机名，流量路径为：该主机名 → Funnel → Ingress-nginx → 只转到 `jasagent-gateway` 的 18721 端口。不得把 `jasagent-web`、Nacos、MySQL、Redis、agent 或其它非网关组件挂到这条公网入口上。Ingress-nginx 与 Funnel 必须允许流式传输，不得整包缓冲，控制台 EventSource 经该入口可用。

Funnel 和 Tailscale 的凭证只放集群 Secret 或仓库外配置，不进 Git。具体主机名、环境地址、命令和集成测试结果写入 `deploy.md`。官方 `scripts/k8s/probe-egress.sh` 以及 015–018 已有的 egress、gVisor 检查范围不得缩小，整体必须 `PROBE PASSED`。018 已落地的每 Pod sidecar 与出站约定保持有效。验收环境以这台云电脑为准，不再依赖 Hang 的 Mac。若创建 Funnel 需要 Hang 的账号或登录，向 Hang 私信办理。

## 数据库

不改表结构。不新增库。口令、令牌仍只从仓库外文件或 Secret 读取，JDBC 地址仍不能带 `password=`。

## 中间件

本轮在部署阶段引入 Ingress-nginx 和 Tailscale Funnel，作为公网入口，不引进新的消息队列或其它业务中间件。Nacos、MySQL、Redis 的访问方式和 017、018 的约定不变。云电脑继续使用 k3s，业务容器（除现有约定的 MySQL、Redis 外）使用 gVisor。本轮不实施 Cloudflare Tunnel，也不使用临时 trycloudflare 链接。

## 开发规范

本轮改到的模块必须同时满足：新增和改动的类、常量、方法都有注释；有对应的测试代码；有可执行的测试脚本。口令、内部令牌、Funnel/Tailscale 凭证和密钥不写进仓库、日志、异常或响应。开发规范里的 K8s 文件按这个时机补：实现和测试阶段不改 `deploy/k8s`；等本轮 ATDD 全部通过后，由部署补 Ingress-nginx、Funnel、入口路由和探测，部署并交出带公网主机名、环境地址、命令和集成测试结果的 `deploy.md`。变更记录不能代替本轮 ATDD。

## 测试要求

按下面几条验收。每条单独做，做完就能判断过不过。变更记录不能代替这些验收。实现提交后由测试角色复跑，并把结果写入 `results/`。其中在仓库内就能判定的合同项，在 ATDD 阶段完成；必须在云电脑集群上观察的项，在部署阶段由部署执行，并写入 `deploy.md`。

1. `jasagent.gateway.web-origin` 和 `JASAGENT_GATEWAY_WEB_ORIGIN` 仍指向本机或集群内的 `jasagent-web` 上游，没有被改成公网主机名或 `*.ts.net`。
2. 当受信任的 Ingress/Funnel 这一跳声明请求是 HTTPS 时，签发和清除的 `JASAGENT_SESSION` 都带 Secure，并仍为 HttpOnly、path `/`、SameSite=Lax。
3. 本机或测试里以明文 HTTP 直连网关、没有受信任转发头时，会话 Cookie 不得被强行加上 Secure。
4. 任意客户端自己带来的 `X-Forwarded-*` 不被信任，不能据此把 Cookie 设成 Secure，也不能据此改跳转或协议。
5. 站点根、登录页和登录后的跳转保持相对路径，响应里的 `Location` 不得写死为 `127.0.0.1` 或 `http://` 绝对地址。
6. 实现只改 `jasagent-gateway`。`jasagent-web` 本轮不被修改。实现和测试提交不修改 `deploy/k8s` 下的清单文件。
7. 网关仍按单副本配置。
8. 本轮改到的模块有注释，有自动测试，并且有可执行的测试脚本。口令、令牌、Funnel/Tailscale 凭证和密钥不出现在仓库、说明或日志里。
9. ATDD 全部通过后，部署在云电脑上建立 Tailscale Funnel，给出稳定的 `*.ts.net` HTTPS 主机名。路径为：该主机名 → Funnel → Ingress-nginx → 只转到 `jasagent-gateway:18721`。
10. 该公网入口不暴露 `jasagent-web`、Nacos、MySQL、Redis、agent 或其它非网关组件。
11. Ingress-nginx 不使用 hostNetwork，并纳入 gVisor。Funnel 与 Ingress 不整包缓冲，控制台 EventSource 经 `https://<*.ts.net>` 可用。
12. 经该 HTTPS 主机名可以打开控制台并完成登录；会话 Cookie 带 Secure；跳转不落到 `127.0.0.1` 或 HTTP。
13. 官方 `scripts/k8s/probe-egress.sh` 整体为 `PROBE PASSED`；现有 egress 与 gVisor 检查范围没有被缩小。015–018 的 I-001 至 I-016 类检查在云电脑上仍然通过。网关仍是单副本。018 的每 Pod sidecar 与出站约定仍然有效。
14. Funnel 和 Tailscale 凭证只放集群 Secret 或仓库外配置，不进 Git。`requirements/019-gateway-public-ingress/deploy.md` 写有公网主机名、环境地址、命令和集成测试结果。验收环境是部署的云电脑，不再依赖 Hang 的 Mac。

第 9 到第 14 条在部署阶段验收。第 1 到第 8 条在 ATDD 阶段验收。

## 还没定

没有还没定的口径。主机名方案已锁定为 Tailscale Funnel 的 `*.ts.net`；具体主机名字符串由部署创建后写入 `deploy.md`。若 Hang 要改成购买域名并接入 Cloudflare Tunnel，须另行确认，本轮不按该路径实施。
