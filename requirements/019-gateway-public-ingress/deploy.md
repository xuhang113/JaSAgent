# 部署记录 019-gateway-public-ingress

**时间**：2026-11-18 08:42 Asia/Shanghai（集群在 11-18 凌晨再次被抹后按 11-17 路径恢复。官方 `probe-egress` 于 08:39:39 通过；015 风格 I-001 至 I-016 于 08:41:42 通过，总结果 `016 IT PASSED`。11-17 首次部署的探测与专项结果见文末历史记录。）

本轮 ATDD（T-001 至 T-008）此前已全部通过（`bd520d6`，2026-11-17 18:37 Asia/Shanghai）。按规格在云电脑上补 Ingress-nginx（不使用 hostNetwork，纳入 gVisor）和只转到 `jasagent-gateway:18721` 的入口，写入 `JASAGENT_GATEWAY_FORWARDED_TRUSTED`（只信任 Ingress 这一跳，当前为 `10.42.0.18/32`，不是 `0.0.0.0/0` 或 `::/0`），并保持官方 `scripts/k8s/probe-egress.sh` 与 015–018 的 egress、gVisor 检查范围。018 的每 Pod sidecar 与 LD_PRELOAD 出站约定仍然有效。验收环境是部署角色的云电脑，不再依赖 Hang 的 Mac。

预定公网主机名是 `https://jasagent-cloud.tail01f87a.ts.net`（本机 Tailscale 已登录，稳定 DNSName 实测为此值，IPv4 `100.69.17.4`，主机名 `jasagent-cloud`）。Funnel 尚未就绪：`bash scripts/k8s/configure-funnel.sh` 在执行 `tailscale serve --bg http://127.0.0.1:30080` 时被控制台拦住，提示该 tailnet 未开启 Serve，需要访问 `https://login.tailscale.com/f/serve?node=nePWyyCqVM11CNTRL`。本机对本机 `https://jasagent-cloud.tail01f87a.ts.net` 的 TLS 握手以 unexpected EOF 失败，`100.69.17.4:443` 连接被拒绝。本轮没有执行 `tailscale up` 或 `tailscale login`，也没有把平台工具当作 JaSAgent 公网 Funnel。因此第 9、12 条里依赖公网主机名的部分，以及第 11 条里「经该主机名打开 EventSource」和第 14 条里集群外浏览器验收，目前仍被挡住。Ingress、一键脚本、官方探测和 I-001 至 I-016 已在恢复后的集群上重跑完成。

## 环境

- 集群：部署角色云电脑上的 k3s（x86_64）
- 节点：`grok-bot-vm-209170388`，Kubernetes `v1.31.4+k3s1`，containerd `1.7.23-k3s2`，操作系统 Debian GNU/Linux 13 (trixie)，内核 `6.12.113+`，节点 Internal-IP `172.30.0.2`，没有 ExternalIP，也没有公网 IP
- 命名空间：`jasagent`（业务），`ingress-nginx`（入口控制器）
- 镜像：本机用官方 `scripts/up-stack.sh` 构建并导入 k3s。Ingress 控制器使用本地包装镜像 `jasagent-ingress-nginx:1.12.4`（基于官方 `registry.k8s.io/ingress-nginx/controller:v1.12.4` 摘要 `sha256:05890cb25d37aa5cfe086614104f798f55e1eeec8dda26d9fd6f6acf0e1554a0`，去掉文件 capabilities 后才能在本机 gVisor 里启动）。默认路径不指向 Hang 的 Mac。
- 滚动结果（2026-11-18 恢复后）：`jasagent-nacos`、`jasagent-metrics-adapter`、`jasagent-gateway`、`jasagent-web`、`jasagent-agentpass`、`jasagent-rag`、`jasagent-grafana`、`jasagent-otel-collector`、`jasagent-prometheus` 均为 1/1 Ready；`jasagent-agent` 两个副本均为 2/2 Ready（容器 `agent` + `sidecar`），并由 HPA 管理。上述工作负载均为 `runtimeClassName=gvisor`。`jasagent-mysql`、`jasagent-redis` 按约定不是 gVisor。`ingress-nginx-controller` 为 1/1 Ready（Pod `ingress-nginx-controller-788bbd94df-jrxz6`），`runtimeClassName=gvisor`，`hostNetwork` 未开启。独立的共享 sidecar Deployment / Service 不存在。
- 网关仍是单副本。`JASAGENT_GATEWAY_WEB_ORIGIN` 仍为 `http://jasagent-web.jasagent.svc.cluster.local:18722`，没有改成公网主机名。
- `JASAGENT_GATEWAY_FORWARDED_TRUSTED` 由 ConfigMap `jasagent-gateway-forwarded` 写入，当前值为 Ingress-nginx 控制器 Pod IP `10.42.0.18/32`。
- Tailscale：官方二进制 1.104.7，`tailscaled` 已在运行（`--operator=box --hostname=jasagent-cloud --accept-dns=false`），`BackendState=Running`，已用 Hang 的账号登录。本记录不写出登录邮箱或凭证。

## 环境地址

| 服务 | 地址 |
| --- | --- |
| 预定公网入口 | `https://jasagent-cloud.tail01f87a.ts.net`。目标路径为该主机名 → Tailscale Funnel → Ingress-nginx NodePort `30080` → 只转到 `jasagent-gateway:18721`。Funnel 尚未创建，见下文阻挡说明 |
| 本机 Ingress-nginx（Funnel 将要转到的端口） | `http://127.0.0.1:30080`（Service `ingress-nginx/ingress-nginx-controller`，ClusterIP `10.43.196.119`，NodePort HTTP 30080 / HTTPS 30443） |
| 网关（集群内） | `http://jasagent-gateway.jasagent.svc.cluster.local:18721`，ClusterIP `10.43.183.90` |
| 控制台上游 jasagent-web（集群内） | `http://jasagent-web.jasagent.svc.cluster.local:18722`，ClusterIP `10.43.106.194` |
| 本机访问网关 | `kubectl -n jasagent port-forward --address 127.0.0.1 svc/jasagent-gateway 18721:18721`，然后访问 `http://127.0.0.1:18721` |
| 发现到的 agent（当时） | `jasagent-agent-0` `10.42.0.9:8080`，`jasagent-agent-1` `10.42.0.10:8080`（均非回环，已在 Nacos 注册） |
| 每 Pod 内 sidecar | `http://127.0.0.1:18761` |
| Nacos | `http://jasagent-nacos.jasagent.svc.cluster.local:8848`，gRPC `9848`，ClusterIP `10.43.197.129` |
| 自定义指标适配器 | `jasagent-metrics-adapter.jasagent.svc.cluster.local:6443`，ClusterIP `10.43.175.19` |
| agentpass | `http://jasagent-agentpass.jasagent.svc.cluster.local:18741`，ClusterIP `10.43.159.39` |
| rag | `http://jasagent-rag.jasagent.svc.cluster.local:18751`，ClusterIP `10.43.149.229` |
| mysql | `jasagent-mysql.jasagent.svc.cluster.local:3306`，ClusterIP `10.43.157.90` |
| redis | `jasagent-redis.jasagent.svc.cluster.local:6379`，ClusterIP `10.43.210.7` |
| grafana | ClusterIP `10.43.20.61`，端口 3000，NodePort 30300 |
| otel-collector | ClusterIP `10.43.8.204`，端口 4318、8889 |
| prometheus | ClusterIP `10.43.106.71`，端口 9090 |
| Ingress 控制器 Pod | `ingress-nginx-controller-788bbd94df-jrxz6`，Pod IP `10.42.0.18` |
| 本机 Tailscale | 主机名 `jasagent-cloud`，稳定主机名 `jasagent-cloud.tail01f87a.ts.net`，IPv4 `100.69.17.4` |

粘性库 JDBC 地址为 `jdbc:mysql://jasagent-mysql.jasagent.svc.cluster.local:3306/jasagent?...`，账号来自 Secret `jasagent-mysql` 的 `username`，口令只从该 Secret 挂载到仓库外口令文件路径的文件读取。JDBC 地址里没有 `password=`。Nacos 在集群内关闭认证。Funnel 和 Tailscale 凭证只放在本机 `tailscaled` 状态或集群外配置，不进 Git。本记录不写出文入口令、令牌或 Cookie 值。

## 一键命令

官方路径：

```bash
bash scripts/k8s/prepare-cluster.sh
bash scripts/up-stack.sh
```

`up-stack.sh` 会先编译本机出站过滤库，再打包、构建并导入镜像（含去掉文件 capabilities 的 Ingress-nginx 包装镜像），apply 清单，删除遗留的共享 sidecar，等待业务 Pod 就绪，安装 Ingress-nginx 与只到网关的 Ingress，按 Ingress 控制器 Pod IP 写入 `jasagent-gateway-forwarded`，刷新本机兜底，最后跑官方 `scripts/k8s/probe-egress.sh`。成功时打印「部署和探测都通过。」

若本机 Tailscale 已经登录到 Hang 的 tailnet，再执行：

```bash
bash scripts/k8s/configure-funnel.sh
```

该脚本不会发起登录或设备码。未登录时以状态码 2 退出。已登录但该 tailnet 尚未在控制台开启 Serve / Funnel / HTTPS 时，也会以状态码 2 退出，并打印 `https://login.tailscale.com/f/serve?...` 链接，而不会一直等待。已登录且 Serve 已开启时，把 Funnel 指到 `http://127.0.0.1:30080`，并打印 `FUNNEL READY https://<*.ts.net>`。

这台云电脑没有可用的 systemd 作为 1 号进程，内核也缺少 VXLAN、ipset、xt_owner、xt_cgroup 等模块，没有 `/lib/modules`，也没有公网 IP，所以还需要下列环境准备：

1. 以进程方式启动 k3s，并加上 `--write-kubeconfig-mode 644 --flannel-backend=host-gw --disable=traefik`（默认 flannel VXLAN 在本机不可用；关掉 Traefik，以免和本轮的 Ingress-nginx 抢 Ingress）。官方 `prepare-cluster.sh` 依赖 systemd，在这台机器上不能单独完成安装，需要按上述参数先把 k3s 拉起来，并预先写好 runsc 的 containerd 模板。
2. Docker 使用静态二进制 29.10.0，`overlay2` 存储，关闭 containerd snapshotter，监听 `unix:///var/run/docker.sock`（不占用已有的 2375 端口）。本机 apt 的 HTTP 源返回 500，Docker、gVisor、iptables 和 Tailscale 二进制改从官方 HTTPS 包安装。`sudo chmod 666 /var/run/docker.sock` 以便非 root 构建镜像。gVisor 本轮仍使用 `release-20261116.0`，`platform=systrap`，`network=host`（hostinet）。iptables 使用 iptables-legacy。
3. 先 `kubectl apply -f deploy/k8s/namespace.yaml`。官方 `up-stack.sh` 会在创建命名空间之前写 Secret，首次会报 `namespaces "jasagent" not found`。
4. 本机残留的 Docker `iptables-legacy` FORWARD 策略是 DROP，会丢掉 cni0 上的 Pod 流量。部署前或探测前执行 `/home/box/bin/jasagent-allow-cni-forward.sh`（在 `DOCKER-USER` 放行 `cni0`）。一键脚本若发现该文件会自动刷新。`jasagent-enforce-agent-egress.sh` 会删除 `inet jasagent_np`，不按 Pod IP 丢包；018 sidecar 与 LD_PRELOAD 仍然保持。
5. 本机内核 `CONFIG_IP_SET` 未开启，k3s 会跳过 NetworkPolicy 控制器。清单里的 NetworkPolicy 仍会写入 API，但不会被 kube-router / NP 控制器执行。不得把「已 apply NetworkPolicy」写成集群已强制执行。
6. 节点没有公网 IP，NodePort 或 Ingress 单独不能接收公网流量，必须再接 Tailscale Funnel。本轮没有使用 Cloudflare Tunnel 或 trycloudflare，也没有把平台 `sand-tailnet-*`、`box-tailnet-chrome`、`start-egress-tunnel` 当作公网 Funnel。
7. 官方 Ingress-nginx 控制器镜像里的 `dumb-init` 和 `nginx-ingress-controller` 带有 `security.capability`。本机 gVisor 启动这类进程会报 `failed to update creds with file privileges: operation not permitted`。因此构建本地包装镜像 `jasagent-ingress-nginx:1.12.4`，拷贝覆盖这些文件以去掉 capabilities。控制器监听 8080/8443，不使用 hostNetwork，不安装 admission webhook。

2026-11-18 早上发现集群进程与 `/var/lib/docker`、`/var/lib/rancher/k3s`、`/etc/rancher/k3s` 再次不在，k3s / kubectl 二进制与辅助脚本仍在。按上述路径重新安装 Docker、gVisor、iptables-legacy，并以进程方式启动 k3s 后，先创建命名空间再写 Secret，随后官方 `bash scripts/up-stack.sh` 打印「部署和探测都通过。」

口令、内部令牌和模型密钥只从仓库外文件注入，例如 `JASAGENT_MYSQL_PASSWORD_FILE`、`JASAGENT_INTERNAL_TOKEN_FILE`、`JASAGENT_LLM_ENV_FILE`。

## 本轮清单与脚本改动

- `deploy/k8s/ingress-nginx.yaml`、`deploy/k8s/ingress-nginx.Dockerfile`：Ingress-nginx 1.12.4，不使用 hostNetwork，纳入 gVisor，Service 为 NodePort 30080/30443。ConfigMap 关闭整包缓冲（`proxy-buffering` / `proxy-request-buffering` 为 off），`proxy-http-version=1.1`，读超时 3600 秒，并给上游设置 `X-Forwarded-Proto: https`。未安装 admission webhook。
- `deploy/k8s/ingress-gateway.yaml`：Ingress `jasagent-gateway` 只转到 `jasagent-gateway:18721`，不写死某个 `*.ts.net` 主机名。不把 `jasagent-web`、Nacos、MySQL、Redis、agent 或其它非网关组件挂到这条入口。
- `deploy/k8s/gateway.yaml`：增加 `JASAGENT_GATEWAY_FORWARDED_TRUSTED`，从 ConfigMap `jasagent-gateway-forwarded` 读取（可选）。`JASAGENT_GATEWAY_WEB_ORIGIN` 不变，副本仍为 1。
- `scripts/up-stack.sh`：构建并导入包装镜像；业务就绪后 apply Ingress，按 Ingress 控制器 Pod IP 写入受信任名单，拒绝 `0.0.0.0/0`、`::/0` 以及前缀为 0 的网段；名单变化时滚动网关。
- `scripts/k8s/probe-egress.sh`：在原有检查之后增加 `ingress-nginx-runtime`、`ingress-only-gateway`、`forwarded-trusted-ingress-only`、`gateway-single-replica-web-origin`。既有 egress 与 gVisor 检查一项都没有删。
- `scripts/k8s/configure-funnel.sh`：仅在 Tailscale 已登录时启用 Funnel，不发起交互登录。2026-11-18 起，若控制台尚未开启 Serve，脚本限时执行并以状态码 2 退出，打印需要 Hang 点击的 `login.tailscale.com/f/serve` 链接，而不会一直等待。

## 集成测试结果（2026-11-18）

### 官方 `scripts/k8s/probe-egress.sh`

测试时间：2026-11-18 08:39:39 Asia/Shanghai。整体结果：`PROBE PASSED`。一键脚本随后打印「部署和探测都通过。」

当时从 Nacos 发现到两台健康实例：`jasagent-agent-0` `10.42.0.9:8080`，`jasagent-agent-1` `10.42.0.10:8080`，都不是回环地址。`discovered-instances` 通过。

`runtime-class` 通过：非 mysql/redis 的业务 Pod（含 gateway、web、发现到的 agent、agentpass、nacos、metrics-adapter、grafana、otel-collector、prometheus、rag）均为 gvisor；mysql 和 redis 按约定不是 gvisor。agent Pod 里两个容器均为 gvisor。

其余原有探测全部通过：`pods-ready`、`gvisor-discovered-agents`、`gvisor-gateway`、`gvisor-agentpass`、`gvisor-sidecar`、`gvisor-nacos`、`gateway-identity`、`agentpass-identity`、`gateway-discovered-agents`、`mysql-discovered-agents`、`redis-discovered-agents`、`agent-direct-external-blocked`（agent 直连被拦，同 Pod sidecar 仍可出站）、`llm-deepseek-no-ticket`、`llm-xai-no-ticket`、`external-no-ticket-denied`、`ticket-issued`、`external-with-ticket`。后四项仍经 agent 容器访问本机回环 `127.0.0.1:18761/v1/forward`。

本轮新增检查也通过：`ingress-nginx-runtime`（`runtime=gvisor`，`hostNetwork=false`，`/proc/version` 含 gvisor）、`ingress-only-gateway`（后端只有 `jasagent-gateway:18721`）、`forwarded-trusted-ingress-only`（`trusted=10.42.0.18/32`）、`gateway-single-replica-web-origin`（副本为 1，web 上游仍是集群内地址）。

### 015 专项（I-001 至 I-016）

测试时间：2026-11-18 08:41:42 Asia/Shanghai。全部通过，总结果：`016 IT PASSED`（沿用 015/016/017/018 同一组检查口径，覆盖 I-001 到 I-016）。

首次在 08:41:06 Asia/Shanghai 重跑时，I-005 失败：全新 MySQL 里还没有 `jasagent_gateway_affinity`（该表按 016 约定在首次读写 / `MysqlAffinityStore.ensure()` 时创建）。随后用与源码一致的 `MysqlAffinityStore.SCHEMA`（`CREATE TABLE IF NOT EXISTS jasagent_gateway_affinity ...`）在集群内 `jasagent` 库建表，其它用例未改。08:41:42 重跑时 I-005 与其余项均通过。

| 用例 | 结果 | 说明 |
| --- | --- | --- |
| I-001-single-replica | 通过 | 网关 Deployment 副本为 1，就绪 1，运行中的 Pod 1 个，容器只有 `gateway` |
| I-002-no-mysql-bridge | 通过 | 网关 Pod 里没有 mysql-bridge 容器 |
| I-003-web-origin | 通过 | `JASAGENT_GATEWAY_WEB_ORIGIN=http://jasagent-web.jasagent.svc.cluster.local:18722` |
| I-004-jdbc-config | 通过 | JDBC 指向集群内 MySQL，地址不含 `password=` |
| I-005-affinity-table | 通过 | 建表后库中存在 `jasagent_gateway_affinity` |
| I-006-gateway-reaches-mysql | 通过 | 网关 Pod 能连到 `jasagent-mysql:3306` |
| I-007-gateway-health | 通过 | `GET /health` 返回 200，正文含 `"status":"up"`，不含 `"storage"` |
| I-008-admin-login-via-gateway | 通过 | 未登录经网关访问 `GET /admin/login` 返回 200 |
| I-009-admin-root-unauth | 通过 | 未登录访问 `GET /admin/` 被重定向到 `/admin/login` |
| I-010-api-me-unauth | 通过 | 未登录 `GET /api/me` 返回 401，`errorCode` 为 `AUTH` |
| I-011-api-cluster-unauth | 通过 | 未登录 `GET /api/cluster` 返回 401，`errorCode` 为 `AUTH` |
| I-012-web-direct-login | 通过 | 直接访问 web 上游 `GET /admin/login` 返回 200 |
| I-013-gateway-reaches-web | 通过 | 网关 Pod 能访问 `jasagent-web:18722/admin/login` 并得到 200 |
| I-014-web-gvisor | 通过 | web Pod 运行在 gVisor |
| I-015-gateway-gvisor | 通过 | 网关 Pod 运行在 gVisor |
| I-016-host-port-forward | 通过 | 本机 `127.0.0.1:18721` 上 `/health` 和 `/admin/login` 均为 200 |

### 经 Ingress NodePort 的入口检查（Funnel 尚未就绪）

测试时间：2026-11-18 08:42 Asia/Shanghai。请求打到本机 `http://127.0.0.1:30080`，`Host: jasagent-cloud.tail01f87a.ts.net`，由 Ingress 补 `X-Forwarded-Proto: https`。这是 Funnel 终止 TLS 后的同一条路径，但还不是公网 `https://jasagent-cloud.tail01f87a.ts.net`。

- `GET /health` 返回 200，正文 `{"status":"up"}`。
- 未登录 `GET /admin`、`GET /admin/`、`GET /admin/cluster`、`GET /nacos/` 的 `Location` 均为相对路径 `/admin/login`，都不含 `127.0.0.1`，也不是 `http://` 绝对地址。未登录 `GET /admin/login` 返回 200 HTML。
- 未登录 `GET /api/me` 返回 401，`errorCode` 为 `AUTH`。未登录进不了控制台。
- `GET /nacos/` 仍由网关处理并转到 `/admin/login`，没有把 Nacos 控制台、web、MySQL、Redis 或 agent 挂到这条入口。
- 用一次性测试账号经 `POST /login` 登录后，`JASAGENT_SESSION` 带 Secure、HttpOnly、path `/`、SameSite=Lax。随后 `GET /api/me` 返回 200（`login` 为该测试账号），`GET /admin` 与 `GET /admin/cluster` 返回 200 HTML（控制台为同一套前端壳）。该测试账号只用于本轮验收，随后已从网关 Pod 删除，口令和会话未写入仓库或本记录。
- 本机明文直连 `http://127.0.0.1:18721` 登录时，Cookie 有 HttpOnly，但没有 Secure。客户端自己带上 `X-Forwarded-Proto: https` 也不会被加上 Secure。
- Ingress 实际生成的 nginx 配置含 `proxy_buffering off`、`proxy_request_buffering off`，并把 `X-Forwarded-Proto` 设为 `https`。没有现成的真实执行流可供端到端打开 EventSource；对不存在的执行访问 `/api/executions/<id>/events` 经 NodePort 立即返回 400（`errorCode` 为 `PERSIST`，`message` 为「执行不存在」），说明这条路径没有把短响应整包卡住，但还不能当成经公网主机名的 EventSource 验收。
- 本机访问 `https://jasagent-cloud.tail01f87a.ts.net/health` 与 `/admin/login` 均因 TLS unexpected EOF 失败；访问 `https://100.69.17.4:443` 连接被拒绝。`tailscale serve status` / `funnel status` 均为 “No serve config”。

### 规格第 9 到第 14 条

| 验收 | 结果 | 说明 |
| --- | --- | --- |
| 9. Funnel 给出稳定的 `*.ts.net` HTTPS 主机名，路径为该主机名 → Funnel → Ingress-nginx → 只转到 `jasagent-gateway:18721` | 未完成 | 预定主机名是 `https://jasagent-cloud.tail01f87a.ts.net`。Ingress-nginx 与只到网关的 Ingress 已就绪，本机 NodePort 30080 已验证。本机 Tailscale 已登录，但 tailnet 未开启 Serve，Funnel 未创建，该主机名目前不能提供 HTTPS |
| 10. 公网入口不暴露 web、Nacos、MySQL、Redis、agent 或其它非网关组件 | 通过（入口清单与本机路径） | Ingress 后端只有 `jasagent-gateway:18721`；经 NodePort 访问 `/nacos/` 仍落到网关登录跳转。公网主机名尚未接上 Funnel |
| 11. Ingress-nginx 不使用 hostNetwork，并纳入 gVisor；Funnel 与 Ingress 不整包缓冲，控制台 EventSource 经 `https://<*.ts.net>` 可用 | 部分通过 | 控制器 `hostNetwork=false`、`runtimeClassName=gvisor`，nginx 已关闭整包缓冲。经 Funnel 主机名的 EventSource 尚未验证 |
| 12. 经该 HTTPS 主机名可以打开控制台并完成登录；会话 Cookie 带 Secure；跳转不落到 `127.0.0.1` 或 HTTP | 部分通过 | 经 Ingress NodePort（Funnel 终止 TLS 后的同一跳，Host 为预定公网主机名）登录成功，Cookie 带 Secure、HttpOnly、path `/`、SameSite=Lax，跳转为相对路径。直连 HTTP 不加 Secure。本机与集群外都还不能打开 `https://jasagent-cloud.tail01f87a.ts.net` |
| 13. 官方探测整体 `PROBE PASSED`；egress 与 gVisor 范围未缩小；I-001 至 I-016 通过；网关单副本；018 sidecar 与出站约定仍有效 | 通过 | 08:39:39 官方探测通过，08:41:42 I-001 至 I-016 通过；网关副本为 1；发现结果为两台非回环 Pod IP:8080；agent 直连外网仍被拦，同 Pod sidecar 可出站 |
| 14. Funnel/Tailscale 凭证不进 Git；`deploy.md` 写有公网主机名、环境地址、命令和集成测试结果 | 部分完成 | 本文件写有预定公网主机名 `https://jasagent-cloud.tail01f87a.ts.net`、环境地址、命令和已做完的测试结果，仓库中无 Tailscale 凭证。Funnel 尚未就绪，集群外 / 手机浏览器验收待 Hang 开启 Serve 并再次配置 Funnel 后进行 |

## 还需要 Hang 做的事

本机 Tailscale 已经用 Hang 的账号登录，设备名为 `jasagent-cloud`，稳定主机名为 `jasagent-cloud.tail01f87a.ts.net`。不要再执行 `tailscale up` 或 `tailscale login`，也不要在群里走设备码。当前挡住 Funnel 的是 tailnet 控制台尚未开启 Serve / Funnel / HTTPS。

请 Hang 用已经登录的 Tailscale 账号在浏览器打开：

https://login.tailscale.com/f/serve?node=nePWyyCqVM11CNTRL

按页面提示为该 tailnet 开启 Serve / Funnel / HTTPS。若该短链过期，可改到 Tailscale 管理控制台（[https://login.tailscale.com/admin](https://login.tailscale.com/admin)）打开 DNS 或 Features 里与 HTTPS、Funnel、Serve 相关的开关，确保 `jasagent-cloud` 所在 tailnet 允许 Funnel。

开启之后，在这台云电脑上重新执行：

```bash
bash scripts/k8s/configure-funnel.sh
```

预期输出类似：`FUNNEL READY https://jasagent-cloud.tail01f87a.ts.net`。然后再用集群外浏览器（含手机）打开：

- `https://jasagent-cloud.tail01f87a.ts.net/admin/login` 完成登录，进入控制台和 `/admin/cluster`
- 未登录访问控制台应被转到 `/admin/login`
- 登录后确认 `JASAGENT_SESSION` 带 Secure、HttpOnly、path `/`、SameSite=Lax
- 若控制台有执行流，确认 EventSource 经该 HTTPS 主机名可用

## 出站与入口的剩余风险

018 已说明的进程级过滤库限制仍然存在：只拦截动态链接的 `connect()`；`env -u LD_PRELOAD` 或纯静态程序可以绕过；UDP 未拦截；过滤库以 hostPath 挂在 `/home/box/lib/libjasagent-egress-filter.so`；NetworkPolicy 仍只是 API 对象。

本轮新增需知悉、不把第 13 条改成失败的事项：

- 本机 gVisor 对全体 runsc 工作负载使用 hostinet。尽管如此，网关看到的受信任对端是 Ingress 控制器 Pod IP `10.42.0.18`，受信任名单按 `/32` 写入，没有放成集群 Pod CIDR 或 `0.0.0.0/0`。若该 Pod 重建导致 IP 变化，需要再跑一键脚本或同等步骤刷新 ConfigMap 并滚动网关。
- 节点 `CIDRAssignmentFailed` 事件仍会出现，但 Pod 实际拿到了 `10.42.0.0/24` 地址，业务与探测未因此失败。
- 这台云电脑的 Docker / k3s 状态已两次被抹（11-17 下午与 11-18 凌晨）。二进制和辅助脚本还在，但进程与 `/var/lib/docker`、`/var/lib/rancher/k3s` 不会自动恢复。

019 在云电脑上的 Ingress、官方探测和 I-001 至 I-016 已在 11-18 恢复后重跑完成。公网 Funnel 待 Hang 在控制台开启 Serve 后再补。

## 2026-11-17 首次部署（历史）

测试时间：官方探测 2026-11-17 18:58:25 Asia/Shanghai，`PROBE PASSED`；I-001 至 I-016 于 19:02:47 通过，总结果 `016 IT PASSED`。当时 `JASAGENT_GATEWAY_FORWARDED_TRUSTED` 为 `10.42.0.19/32`（Ingress Pod `ingress-nginx-controller-788bbd94df-c6smb`）。当时本机尚未登录 Tailscale，没有 `*.ts.net` 主机名，Funnel 未创建。ClusterIP 与 11-18 恢复后不同，不应再当作当前地址使用。当时经 NodePort 的登录、相对跳转、Secure Cookie 与「直连 HTTP 不加 Secure」结论与 11-18 一致。
