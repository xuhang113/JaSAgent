# 部署记录 026-gateway-session-media

**时间**：2026-11-27（Asia/Shanghai）。本文件写规格第 9 到第 12 条在部署云电脑上的结果。验收环境是这台云电脑，不再依赖 Hang 的 Mac。口令、令牌、Funnel/Tailscale 凭证、Authorization、Cookie 和密钥原文不写入本文件。

ATDD 第 1 到第 8 条已在 [PR #19](https://github.com/xuhang113/JaSAgent/pull/19) 通过，头提交是 [`6d3afbe`](https://github.com/xuhang113/JaSAgent/commit/6d3afbe2c61da97607bbddc03fb9b98fc411e772)（2026-11-27 15:57 Asia/Shanghai），产品实现基线是 [`35ac67bb`](https://github.com/xuhang113/JaSAgent/commit/35ac67bb1e8e710e5abd86e6a75ca048b4384154)。领队已独立核对 T-001 至 T-008 均为通过，并与已确认 spec 第 1–8 条对得上。该实现 PR 现在仍不合入。本分支从当时的 #19 头提交另开，只增加 `deploy/k8s` 和本记录，不改产品代码、测试或官方集群脚本，也不直接推 master。在 #19 合入之前，本 PR 会带上 #19 的提交，这是预期行为；本 PR 只覆盖规格第 9 到第 12 条，不能用来合入产品代码。

本轮是网关会话多媒体。附件由网关自己处理，写入已有 MySQL 库 `jasagent` 的 `session_attachment`，不新建库。Funnel / Tailscale 不在本轮，没有恢复，也不把公网入口或历史主机名写成可用。

## 环境

- 节点：`grok-bot-vm-209170388`，Kubernetes `v1.31.4+k3s1`，containerd `1.7.23-k3s2`，操作系统 Debian GNU/Linux 13 (trixie)，内核 `6.12.113+`，节点 Internal-IP `172.30.0.2`，没有 ExternalIP
- 命名空间：`jasagent`（业务），`ingress-nginx`（入口控制器）
- 网关副本：实测 `spec.replicas=2` 且 `readyReplicas=2`。第 9 条重建后的两个 Pod 是 `jasagent-gateway-7c478d86b-vnxdv`（`10.42.0.69`）和 `jasagent-gateway-7c478d86b-wgm56`（`10.42.0.68`）
- `JASAGENT_GATEWAY_WEB_ORIGIN`：`http://jasagent-web.jasagent.svc.cluster.local:18722`
- `JASAGENT_GATEWAY_JAEGER_ORIGIN`：`http://jasagent-jaeger.jasagent.svc.cluster.local:16686`（只含根地址，不含路径）
- `JASAGENT_GATEWAY_FORWARDED_TRUSTED`：`10.42.0.63/32`（Ingress 控制器 Pod IP），不是 `0.0.0.0/0` 或 `::/0`
- Ingress 后端仍只有 `jasagent-gateway:18721`。web、Nacos、MySQL、Redis、agent、agentpass、Jaeger 都不在 Ingress 上。附件没有单独的 Service。
- 本轮不执行 `tailscale up` 或 `tailscale login`，也不验收 Funnel。验收时 `tailscaled` 未运行，`/var/lib/tailscale` 不存在。历史主机名不是可用入口。
- 业务容器除 MySQL、Redis 外仍使用 gVisor。官方探测没有删除或跳过 018–025 的既有项
- 业务镜像用本分支上的 [PR #19](https://github.com/xuhang113/JaSAgent/pull/19) 产品提交打包，清单用本 PR。#19 仍未合入。

本轮云电脑在验收前是空集群：`/var/lib/rancher`、`/etc/rancher` 和 `/var/lib/docker` 都不在。本机 Docker 静态二进制是 29.10.0，以进程方式运行 dockerd，`/etc/docker/daemon.json` 为 `"storage-driver": "overlay2"` 且 `"features": {"containerd-snapshotter": false}`、`"iptables": true`。用官方 `bash scripts/k8s/prepare-cluster.sh` 于 18:21:09（Asia/Shanghai）拉起 k3s（flannel `host-gw`，RuntimeClass `gvisor` 已创建）。全新集群上官方脚本先执行 `write_secrets` 再 `apply_stack` 会因命名空间还不存在而失败；本轮没有改官方脚本的这一顺序，先执行了 `kubectl apply -f deploy/k8s/namespace.yaml`，再跑官方 `bash scripts/up-stack.sh`。

官方脚本从 `gvisor/releases/release/latest` 装到的是 `release-20261123.1`，其 `containerd-shim-runsc-v1` 对 k3s 自带的 containerd 1.7.23 会报 `unsupported shim version (3)`，gVisor Pod 起不来。本轮没有改官方脚本，只在宿主机把 runsc 换成此前 023 用过且能工作的 `release-20261116.0`，然后按同样参数重新拉起 k3s 进程。之后 gVisor 工作负载（含 Nacos、网关、agent）可以就绪。

内核没有 `/lib/modules`，也没有 `xt_statistic`。k3s kube-proxy 的 `iptables-restore` 仍然会报 `Extension statistic revision 0 not supported, missing kernel module?`。本轮没有改成 IPVS。网关是双副本，这条失败会让部分 Service 的 DNAT 在 Pod IP 变化后停留在旧地址。第一次随 `up-stack.sh` 跑官方探测时，Nacos ClusterIP 仍指向已删除的旧 Pod，结果是 `PROBE FAILED`（先是 `nacos-query-failed`，随后发现列表为空，连带 sidecar / 出站项拿不到 agent Pod）。在宿主机把已有 Nacos（单端点）和网关 ClusterIP 的 DNAT 改到当时还活着的 Pod IP，并重启 agent 重新向 Nacos 注册后，官方探测可以从宿主机访问 Nacos ClusterIP。这只是云电脑上的网络兜底，没有改官方探测脚本，也没有缩小检查范围。

## 环境地址

| 服务 | 实测地址 |
| --- | --- |
| 本机 Ingress-nginx NodePort | Service `ingress-nginx/ingress-nginx-controller` 为 NodePort HTTP 30080 / HTTPS 30443，ClusterIP `10.43.2.239`，控制器 Pod `ingress-nginx-controller-788bbd94df-kshfc`，Pod IP `10.42.0.63`。Ingress 对象 `jasagent/jasagent-gateway` 的后端仍只有 `jasagent-gateway:18721`，并带有 `nginx.ingress.kubernetes.io/proxy-body-size: 64m`。2026-11-27 18:41:41 至 18:41:48（Asia/Shanghai）从宿主机访问 `127.0.0.1:30080/health` 和 `172.30.0.2:30080/health` 返回 HTTP 200、正文 `{"status":"up"}`，当时 `KUBE-NODEPORTS` 里有 30080 规则。这不是公网入口：节点没有 ExternalIP，Funnel / Tailscale 未运行，历史主机名不能写成可用。kube-proxy 因缺少 `xt_statistic` 仍无法稳定刷新全部规则，Pod 重建后 ClusterIP/NodePort 仍可能再次指向旧地址。第 9 条附件验收走的是网关 Pod IP。 |
| 公网 Funnel | 本轮不验收、也没有恢复。没有执行 `tailscale up` 或 `tailscale login`。`tailscaled` 未运行，`/var/lib/tailscale` 不存在。不把历史主机名写成可用。 |
| 网关（集群内） | `http://jasagent-gateway.jasagent.svc.cluster.local:18721`，ClusterIP `10.43.245.50`。第 9 条重建后两个就绪副本的 Pod IP 是 `10.42.0.69` 和 `10.42.0.68` |
| 控制台上游 jasagent-web（集群内） | `http://jasagent-web.jasagent.svc.cluster.local:18722`，ClusterIP `10.43.30.78`，Pod IP `10.42.0.55` |
| Jaeger query（仅 ClusterIP） | `http://jasagent-jaeger.jasagent.svc.cluster.local:16686`，ClusterIP `10.43.168.188`，Pod IP `10.42.0.56` |
| otel-collector OTLP HTTP traces | `http://jasagent-otel-collector.jasagent.svc.cluster.local:4318/v1/traces`，ClusterIP `10.43.22.75`，Pod IP `10.42.0.57` |
| agentpass | `http://jasagent-agentpass.jasagent.svc.cluster.local:18741`，ClusterIP `10.43.174.0`，Pod IP `10.42.0.50` |
| Nacos | `http://jasagent-nacos.jasagent.svc.cluster.local:8848`，gRPC `9848`，ClusterIP `10.43.17.146`，Pod IP `10.42.0.62` |
| rag | `http://jasagent-rag.jasagent.svc.cluster.local:18751`，ClusterIP `10.43.243.66`，Pod IP `10.42.0.60` |
| mysql | `jasagent-mysql.jasagent.svc.cluster.local:3306`，ClusterIP `10.43.12.165`，Pod `jasagent-mysql-5d84c4846f-8vkn7`，Pod IP `10.42.0.8`。库仍是已有的 `jasagent`，`@@max_allowed_packet=104857600`。本轮是环境重建后的新数据目录 |
| redis | `jasagent-redis.jasagent.svc.cluster.local:6379`，ClusterIP `10.43.106.120`，Pod IP `10.42.0.5` |
| 发现到的 agent | `jasagent-agent-0` `10.42.0.67:8080`，`jasagent-agent-1` `10.42.0.66:8080`，Service 为 ClusterIP None |

## 本轮清单

- `deploy/k8s/gateway.yaml`：注释写明附件表 `session_attachment` 与账号、登录会话共用已有 MySQL，以及网关 `home` emptyDir 不是附件存储，也不能把两个副本挂到同一块普通 RWO 盘。副本数仍是 2，JDBC 环境变量不变，不增加附件卷。
- `deploy/k8s/mysql.yaml`：注释写明同一库承载附件；为 50 MiB 视频正文增加 `--max-allowed-packet=104857600`。仍是已有的那一个 MySQL 实例和 PVC，不另建库。实测 `@@max_allowed_packet=104857600`。
- `deploy/k8s/ingress-gateway.yaml`：Ingress 后端仍只有 `jasagent-gateway:18721`，不另暴露附件服务。增加 `nginx.ingress.kubernetes.io/proxy-body-size: "64m"`，避免 Ingress 默认 1m 先拒绝合法上传。不写历史 `*.ts.net` 主机名。
- 本文件记录环境地址、命令和集成测试结果。

没有改 [PR #19](https://github.com/xuhang113/JaSAgent/pull/19) 的产品代码，也没有改 sidecar、agentpass、otel、官方 `scripts/k8s/prepare-cluster.sh`、`scripts/up-stack.sh` 或 `scripts/k8s/probe-egress.sh`。凭证不进 Git。

## 验收命令

```bash
export PATH="/usr/sbin:/home/box/bin:$PATH"
export JASAGENT_K3S_BIN=/home/box/opt/k3s
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
export JAVA_HOME=/home/box/opt/java
bash scripts/k8s/prepare-cluster.sh
kubectl apply -f deploy/k8s/namespace.yaml
bash scripts/up-stack.sh
bash scripts/k8s/probe-egress.sh
```

没有执行 `tailscale up` 或 `tailscale login`。没有跳过 `ticket-issued` 或 `external-with-ticket`。官方探测须整体为 `PROBE PASSED`，018–025 既有项不缩小。

## 集成测试结果

### 官方探测

通过。官方 `bash scripts/up-stack.sh` 在清单应用后第一次自动跑探测，结果是 `PROBE FAILED`。失败原因写在上文：Nacos ClusterIP 的 DNAT 仍指向已删除的旧 Pod，宿主机查询失败，发现列表为空。本轮没有改探测脚本，也没有跳过既有项。

在同一库 `jasagent` 写入探测用账号 `stack`、登录会话 `s1` 和 `net.egress` 允许策略（与 023 / 025 相同原因：官方探测仍按文件会话准备用户，023 发证只认集群 MySQL 里的会话和策略），并在宿主机纠正过期 DNAT、等两台 agent 重新注册之后，于 18:40:43（Asia/Shanghai）重跑官方 `bash scripts/k8s/probe-egress.sh`。脚本退出码 0，打印 `PROBE PASSED`。完整日志在云电脑 `/tmp/026-probe-egress.log`。探测开始于 18:40:37（Asia/Shanghai）。结果包括：`PASS discovered-instances`、`PASS pods-ready`、`PASS runtime-class`、`PASS gvisor-discovered-agents`、`PASS gvisor-gateway`、`PASS gvisor-agentpass`、`PASS gvisor-sidecar`、`PASS gvisor-nacos`、网关与 agentpass 身份、发现到的 agent 均可访问，以及 018 的出站约定：`PASS agent-direct-external-blocked`、`PASS llm-deepseek-no-ticket`、`PASS llm-xai-no-ticket`、`PASS external-no-ticket-denied`、`PASS ticket-issued`、`PASS external-with-ticket`。019 / 020 / 024 入口和副本检查也仍在：`PASS ingress-nginx-runtime`、`PASS ingress-only-gateway`（`jasagent-gateway:18721`）、`PASS forwarded-trusted-ingress-only`（`trusted=10.42.0.63/32`）、`PASS gateway-multi-replica-web-origin`（`replicas=2 ready=2 origin=http://jasagent-web.jasagent.svc.cluster.local:18722`）。025 项为 `PASS jaeger-clusterip-not-nodeport`（`type=ClusterIP`，没有 nodePort）和 `PASS jaeger-not-on-ingress`（后端仍是 `jasagent-gateway:18721`）。探测脚本相对仓库没有再改，018–025 的检查范围没有缩小，也没有跳过既有项。

### 第 9 条：删或重建网关 Pod 后附件仍可取回，不新建库，双副本不共用普通 RWO 盘

通过。2026-11-27 18:41:28 至 18:41:41（Asia/Shanghai）在云电脑上走网关 Pod IP（不是公网入口）验收。两个就绪副本当时是 `jasagent-gateway-7c478d86b-9gcfx` 和 `jasagent-gateway-7c478d86b-kkxz2`。未登录访问附件上传和下载均返回 401，且 `errorCode` 为 `AUTH`。已登录用户经其中一个就绪副本上传一份允许的 PNG（70 字节）后，从「不是接收上传的那一个就绪副本」取回同一份字节，下载响应带 `X-Content-Type-Options: nosniff`，并且没有用下载响应改写 `JASAGENT_SESSION`。随后删除全部网关 Pod；重建出的 `jasagent-gateway-7c478d86b-vnxdv`（`10.42.0.69`）和 `jasagent-gateway-7c478d86b-wgm56`（`10.42.0.68`）于 18:41:41 就绪后，两个新副本都能取回与上传完全相同的字节，同样带 nosniff，也不改写登录 Cookie。

集群里用户库仍然只有 `jasagent`，没有另建库。`session_attachment` 在该库中，当时有 1 行。网关 Deployment 的卷仍是 `home=emptyDir`、`tmp=emptyDir` 和口令 Secret，没有 PVC，没有把两个副本挂到同一块普通 RWO 盘。MySQL 仍是已有实例，`max_allowed_packet` 为 100M。

### 第 10 条：官方探测整体通过且未缩小范围

通过。见上文「官方探测」。`PROBE PASSED` 包含 018–025 既有项。

### 第 11 条：Ingress 仍只转到网关，不另暴露附件服务

通过。官方探测 `PASS ingress-only-gateway` 和 `PASS jaeger-not-on-ingress` 的后端都是 `jasagent-gateway:18721`。Ingress 对象只有这一条后端，并设置了 `proxy-body-size: 64m`。web、Nacos、MySQL、Redis、agent、agentpass、Jaeger 都没有出现在 Ingress 上。没有单独的附件 Service。Grafana 仍有既有 NodePort，不是本轮入口。

### 第 12 条：以 PR 提交清单和本记录，不恢复 Funnel，不把公网入口写成可用

清单和本记录在本部署 PR 提交，环境地址和集成测试结果已回写到同一分支，没有直接推 master，也没有合入 [PR #19](https://github.com/xuhang113/JaSAgent/pull/19)。验收环境是部署的云电脑，没有改 Hang 的 Mac。本轮没有恢复 Funnel，没有执行 `tailscale up` 或 `tailscale login`，`tailscaled` 未运行，没有把公网入口或历史主机名写成可用。节点没有 ExternalIP。NodePort 30080 在本次核对当时可以访问 `/health`，但只是这台云电脑节点上的端口，不能当成公网入口，也不能替代已搁置的 Funnel。

本 PR 等审查和领队核对后再合并，部署侧现在不合并。
