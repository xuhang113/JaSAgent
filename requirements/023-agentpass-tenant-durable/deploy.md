# 部署记录 023-agentpass-tenant-durable

**时间**：2026-11-20（Asia/Shanghai）。本文件写规格第 10 到第 12 条在部署云电脑上的实测结果。验收环境是这台云电脑，不再依赖 Hang 的 Mac。口令、令牌、票据和签名密钥原文不写入本文件。

ATDD 第 1 到第 9 条已在 [PR #13](https://github.com/xuhang113/JaSAgent/pull/13) 的 `d23afbf97c8649e0738cefd0cacc9e020e7426bb` 通过，产品实现提交是 `ef848d6a7258b9c09f88552010483939148abccf`，结果在 `requirements/023-agentpass-tenant-durable/results/`。本文件是在该提交的镜像上到集群验收后回写的结果。本轮没有直接推 master。本文件所在 PR 只含 `deploy/k8s`、`scripts/up-stack.sh` 和本记录，不含产品代码。

## 环境

- 节点：`grok-bot-vm-209170388`，Kubernetes `v1.31.4+k3s1`，containerd `1.7.23-k3s2`，操作系统 Debian GNU/Linux 13 (trixie)，内核 `6.12.113+`，节点 Internal-IP `172.30.0.2`，没有 ExternalIP
- 命名空间：`jasagent`（业务），`ingress-nginx`（入口控制器）
- 网关副本：1。实测 `spec.replicas=1` 且 `readyReplicas=1`
- `JASAGENT_GATEWAY_WEB_ORIGIN`：`http://jasagent-web.jasagent.svc.cluster.local:18722`
- `JASAGENT_GATEWAY_FORWARDED_TRUSTED`：`10.42.0.18/32`（Ingress 控制器 Pod IP），不是 `0.0.0.0/0` 或 `::/0`
- 公网路径原先是 `https://jasagent-cloud.tail01f87a.ts.net` → Tailscale Funnel → Ingress-nginx NodePort `30080` → 只转到 `jasagent-gateway:18721`。2026-11-20 早晨云电脑更新后，`/var/lib/tailscale` 不在了，本轮没有恢复 Funnel，也没有执行 `tailscale up` 或 `tailscale login`
- Ingress 后端仍只有 `jasagent-gateway:18721`。agentpass 只有 ClusterIP，没有出现在 Ingress 或 Funnel 上
- 业务容器除 MySQL、Redis 外仍使用 gVisor。官方 `scripts/k8s/probe-egress.sh` 本轮没有修改，既有 egress、sidecar 线程、运行时异步与 gVisor 检查没有删除或跳过
- agentpass 生产环境通过 Secret `jasagent-agentpass` 的 `signing-key` 注入 `JASAGENT_AGENTPASS_SIGNING_KEY`，通过 Secret `jasagent-mysql` 的 `jdbc-url`、`username`、`password` 注入 `JASAGENT_JDBC_*`。密钥只放在仓库外文件和集群 Secret，不进 Git、日志或本文件。缺少该密钥时 `up-stack.sh` 会失败，不会静默生成

## 环境地址

| 服务 | 实测地址 |
| --- | --- |
| 公网入口 | 历史主机名仍是 `https://jasagent-cloud.tail01f87a.ts.net`。本轮 Funnel 状态文件不在，公网 `/health` 未复查。集群内入口仍是 Ingress-nginx NodePort `30080` → 只转到 `jasagent-gateway:18721` |
| 本机 Ingress-nginx | `http://127.0.0.1:30080`（Service `ingress-nginx/ingress-nginx-controller`，NodePort HTTP 30080 / HTTPS 30443，ClusterIP `10.43.31.29`） |
| 网关（集群内） | `http://jasagent-gateway.jasagent.svc.cluster.local:18721`，ClusterIP `10.43.133.229`。网关 Pod 是 `jasagent-gateway-5fffdfc998-gp7gw`，Pod IP `10.42.0.19` |
| 控制台上游 jasagent-web（集群内） | `http://jasagent-web.jasagent.svc.cluster.local:18722`，ClusterIP `10.43.191.71`，Pod `jasagent-web-56f98687f8-8mql7`，Pod IP `10.42.0.12` |
| 本机访问网关 | `kubectl -n jasagent port-forward --address 127.0.0.1 svc/jasagent-gateway 18721:18721`，然后访问 `http://127.0.0.1:18721` |
| 发现到的 agent（官方探测当时） | `jasagent-agent-0` `10.42.0.9:8080`，`jasagent-agent-1` `10.42.0.10:8080`（均非回环，已在 Nacos 注册） |
| 每 Pod 内 sidecar | `http://127.0.0.1:18761`。sidecar 容器镜像是 `jasagent-sidecar:0.1.0`（镜像 ID `sha256:67e9cf5e8e8cb7d3588b3ef9b8b1b934bdeca880914e54ad3a80e78db804eaf6`） |
| agent 容器镜像 | `jasagent-agent:0.1.0`（镜像 ID `sha256:212d3a0b75823e0a30012aa05d911d3b7eb73c87282a2604fc5b35a02131de46`），来自 [PR #13](https://github.com/xuhang113/JaSAgent/pull/13) 的 `ef848d6` |
| agentpass 容器镜像 | `jasagent-agentpass:0.1.0`（镜像 ID `sha256:a0067e6f21466130ec1e523dfb05b20777973b60ad82d720916695a464bdb49f`），来自 [PR #13](https://github.com/xuhang113/JaSAgent/pull/13) 的 `ef848d6`。Service 是 ClusterIP `10.43.224.27`，地址 `http://jasagent-agentpass.jasagent.svc.cluster.local:18741`，未暴露到公网 |
| 网关容器镜像 | `jasagent-gateway:0.1.0`（镜像 ID `sha256:1de8c8de5693d04d66c8e386ecb7c6f384a814af06239d25f7e1432057cf0109`） |
| 控制台镜像 | `jasagent-web:0.1.0`（镜像 ID `sha256:3ca002c713840794a5b1e4a84b150e21e07fe2c04357feb50ec35b4f039d1af4`） |
| Nacos | `http://jasagent-nacos.jasagent.svc.cluster.local:8848`，gRPC `9848`，ClusterIP `10.43.251.89` |
| rag | `http://jasagent-rag.jasagent.svc.cluster.local:18751`，ClusterIP `10.43.88.68` |
| mysql | `jasagent-mysql.jasagent.svc.cluster.local:3306`，ClusterIP `10.43.126.51`，Pod `jasagent-mysql-565b56b555-b2npd`，Pod IP `10.42.0.6`，uid `b5c1614d-7647-4aa5-86f3-f0345b87f5d0`。本轮是早晨更新后重建的集群，没有在验收过程中删除这个 Pod |
| redis | `jasagent-redis.jasagent.svc.cluster.local:6379`，ClusterIP `10.43.240.82`，Pod `jasagent-redis-679668c9f-xptjj`，Pod IP `10.42.0.5` |
| Ingress 控制器 Pod | `ingress-nginx-controller-788bbd94df-sj4vk`，Pod IP `10.42.0.18` |

## 本轮清单

本轮需要给 agentpass 增加签名密钥 Secret 和 JDBC 环境变量，已写入 `deploy/k8s/agentpass.yaml`，并在 `deploy/k8s/secret.yaml` 注释里说明 Secret 名称和键名，不写值。`scripts/up-stack.sh` 从仓库外文件 `JASAGENT_AGENTPASS_SIGNING_KEY_FILE`（默认 `${HOME}/.config/jasagent/agentpass-signing.key`）创建 Secret `jasagent-agentpass` / `signing-key`；文件缺失时脚本失败，不会静默生成。agentpass Service 仍是 ClusterIP，端口仍是 18741，没有对公网暴露。没有改 `scripts/k8s/probe-egress.sh`。

## 验收命令

2026-11-20 早晨云电脑更新后，本机 k3s 和 Tailscale 状态都不在了。这台机器没有可用的 systemd，官方 `scripts/k8s/prepare-cluster.sh` 不能单独装完。实际按 019 的办法恢复集群，再用 [PR #13](https://github.com/xuhang113/JaSAgent/pull/13) 的 `d23afbf`（产品实现 `ef848d6`）检出后执行官方一键脚本：

```bash
# 预先写好 runsc 的 containerd 模板后，以进程方式启动 k3s
# /home/box/opt/k3s server --write-kubeconfig-mode 644 --flannel-backend=host-gw --disable=traefik
# 安装 gVisor runsc（release-20261116.0），apply deploy/k8s/runtimeclass-gvisor.yaml
# 先 apply deploy/k8s/namespace.yaml，再跑官方一键脚本
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
bash scripts/up-stack.sh
# 第一次官方探测因集群 MySQL 里还没有发证会话而失败（ticket-issued、external-with-ticket）
# 按探测请求补上 tenantId=local、userId=stack、sessionId=s1，以及 toolName=net.egress 的 ALLOW 策略后重跑
bash scripts/k8s/probe-egress.sh
kubectl -n jasagent port-forward --address 127.0.0.1 svc/jasagent-gateway 18721:18721
# 然后跑本机 015 风格 I-001 至 I-016 运行器（不在仓库里）
```

本轮没有修改 `scripts/k8s/prepare-cluster.sh`、`scripts/k8s/configure-funnel.sh` 或 `scripts/k8s/probe-egress.sh`，也没有跳过 `ticket-issued` 或 `external-with-ticket`。没有执行 `tailscale up` 或 `tailscale login`。

## 集成测试结果

### 官方 `scripts/k8s/probe-egress.sh`

通过。第一次随 `up-stack.sh` 在 15:26:44（Asia/Shanghai）左右结束时是 `PROBE FAILED`，失败项只有 `ticket-issued` 和随后的 `external-with-ticket`，其余项已通过。原因是 023 发证只认集群 MySQL 里的会话和策略，官方探测脚本仍按文件会话准备用户，本轮没有改这份脚本。在库 `jasagent` 写入探测用的登录会话和 `net.egress` 允许策略后，于 19:13:44（Asia/Shanghai）重跑，脚本退出码 0，打印 `PROBE PASSED`。结果包括：`PASS pods-ready`、`PASS runtime-class`、`PASS gvisor-discovered-agents`、`PASS gvisor-gateway`、`PASS gvisor-agentpass`、`PASS gvisor-sidecar`、`PASS gvisor-nacos`、网关与 agentpass 身份、发现到的 agent 均可访问，以及 018 的出站约定：`PASS agent-direct-external-blocked`、`PASS llm-deepseek-no-ticket`、`PASS llm-xai-no-ticket`、`PASS external-no-ticket-denied`、`PASS ticket-issued`、`PASS external-with-ticket`。019 / 020 入口检查也仍在：`PASS ingress-nginx-runtime`、`PASS ingress-only-gateway`（`jasagent-gateway:18721`）、`PASS forwarded-trusted-ingress-only`（`trusted=10.42.0.18/32`）、`PASS gateway-single-replica-web-origin`（`replicas=1 ready=1 origin=http://jasagent-web.jasagent.svc.cluster.local:18722`）。探测脚本相对仓库没有改动，egress、sidecar 线程、运行时异步与 gVisor 检查范围没有缩小。018 的每 Pod sidecar 与出站约定、021 / 022 的验收范围仍然有效。

### 015 风格 I-001 至 I-016

通过。19:15:49（Asia/Shanghai）使用本机运行器 `/tmp/jasagent-023-deploy/run-it-001-016.sh`（仓库里没有这份脚本；沿用 015 口径）。I-001 至 I-016 全部为 `PASS`，摘要为 `016 IT PASSED`。网关仍是单副本；库 `jasagent` 里能看到 `jasagent_account`、`jasagent_login_session`、`jasagent_gateway_affinity` 和 `agent_session`；JDBC 地址不含 `password=`；网关与 web 的 `/proc/version` 均为 gVisor。全新 MySQL 里起初没有 `jasagent_gateway_affinity`，按 019 同样办法用源码里的 `MysqlAffinityStore.SCHEMA` 建表后，I-005 通过。

### 公网 Funnel

未复查。早晨更新后 `/var/lib/tailscale` 不在了，本轮没有执行 `tailscale up` 或 `tailscale login`。若要恢复 Funnel，需要 Hang 在私信里重新登录。Ingress 后端仍只有 `jasagent-gateway:18721`，agentpass 没有暴露到公网。

### 规格第 10 到第 12 条

| 验收 | 结果 | 说明 |
| --- | --- | --- |
| 10. 官方探测整体为 `PROBE PASSED`；现有 egress、sidecar 线程、运行时异步与 gVisor 检查范围没有被缩小。018 / 021 / 022 在云电脑上已通过的集成检查仍然成立。网关仍是单副本。agentpass 没有暴露到公网。015 / 016 / 017 / 018 / 020 / 021 / 022 在云电脑上已通过的集成检查仍然成立 | 通过（公网 Funnel 未复查） | 官方探测于 19:13:44 为 `PROBE PASSED`，没有改探测脚本，也没有跳过 `ticket-issued` 或 `external-with-ticket`。I-001 至 I-016 于 19:15:49 全部通过。网关仍是单副本。agentpass 仍是 ClusterIP，Ingress 只转到网关。公网 `/health` 因 Funnel 状态文件不在，本轮未复查 |
| 11. 若本轮需要把签名密钥挂进 Secret，或调整 agentpass 的 Service、环境变量，已由部署开 PR 调整，而不是直接推 master。生产缺少该密钥必须失败。密钥不在 Git、日志或说明里 | 通过 | 本文件所在 PR 增加了 Secret `jasagent-agentpass` / `signing-key`、agentpass 的 `JASAGENT_AGENTPASS_SIGNING_KEY` 与 `JASAGENT_JDBC_*`，以及 `up-stack.sh` 从仓库外文件写入该 Secret。文件缺失时脚本失败。密钥原文不在 Git、日志或本文件。没有直接推 master |
| 12. 本文件写有环境地址、命令和集成测试结果。验收环境是部署的云电脑，不再依赖 Hang 的 Mac | 通过 | 见上文。本文件由部署开 Pull Request 提交 |

本轮没有把 023 整块宣布完成。等 [PR #13](https://github.com/xuhang113/JaSAgent/pull/13) 和本文件所在 PR 经审查合进 master 后，再由领队宣布。
