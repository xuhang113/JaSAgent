# 部署记录 022-runtime-full-async

**时间**：2026-11-19（Asia/Shanghai）。本文件写规格第 10 到第 12 条在部署云电脑上的实测结果。验收环境是这台云电脑，不再依赖 Hang 的 Mac。口令、令牌、票据和密钥不写入本文件。

ATDD 第 1 到第 9 条已在 [PR #11](https://github.com/xuhang113/JaSAgent/pull/11) 的 `95db3394eda4783e11efefe681a10dd758240c43` 通过，产品实现提交是 `197ffe6e1125c84be823a944ecaa53dc8fc592eb`，结果在 `requirements/022-runtime-full-async/results/`。本文件是在该提交的 agent 镜像上到集群验收后回写的结果。本轮没有修改 `deploy/k8s`，也没有直接推 master。

## 环境

- 节点：`grok-bot-vm-209170388`，Kubernetes `v1.31.4+k3s1`，containerd `1.7.23-k3s2`，操作系统 Debian GNU/Linux 13 (trixie)，内核 `6.12.113+`，节点 Internal-IP `172.30.0.2`，没有 ExternalIP
- 命名空间：`jasagent`（业务），`ingress-nginx`（入口控制器）
- 网关副本：1。实测 `spec.replicas=1` 且 `readyReplicas=1`
- `JASAGENT_GATEWAY_WEB_ORIGIN`：`http://jasagent-web.jasagent.svc.cluster.local:18722`
- `JASAGENT_GATEWAY_FORWARDED_TRUSTED`：`10.42.0.18/32`（Ingress 控制器 Pod IP），不是 `0.0.0.0/0` 或 `::/0`
- 公网路径仍是 `https://jasagent-cloud.tail01f87a.ts.net` → Tailscale Funnel → Ingress-nginx NodePort `30080` → 只转到 `jasagent-gateway:18721`
- 业务容器除 MySQL、Redis 外仍使用 gVisor。官方 `scripts/k8s/probe-egress.sh` 本轮没有修改，既有 egress、sidecar 线程与 gVisor 检查没有删除
- 本轮没有执行 `tailscale up` 或 `tailscale login`
- agent 生产默认仍是 `jasagent.app.tomcat-platform-workers=0`（不替换 Tomcat 自己的线程池）。sidecar 生产默认仍是 `jasagent.sidecar.tomcat-platform-workers=0`。agent 清单里没有压低线程上限的环境变量或启动参数
- agent StatefulSet 的 HPA 仍是最小 2、最大 10。滚动前后副本数随 CPU 在 3 到 5 之间变化，本轮没有改 HPA，也没有把副本数钉死

## 环境地址

| 服务 | 实测地址 |
| --- | --- |
| 公网入口 | `https://jasagent-cloud.tail01f87a.ts.net`。路径为该主机名 → Tailscale Funnel → Ingress-nginx NodePort `30080` → 只转到 `jasagent-gateway:18721`。从这台云电脑访问时使用 `curl --resolve jasagent-cloud.tail01f87a.ts.net:443:<公网 IP>` |
| 本机 Ingress-nginx | `http://127.0.0.1:30080`（Service `ingress-nginx/ingress-nginx-controller`，NodePort HTTP 30080 / HTTPS 30443） |
| 网关（集群内） | `http://jasagent-gateway.jasagent.svc.cluster.local:18721`，ClusterIP `10.43.183.90`。网关 Pod 是 `jasagent-gateway-7fd99d7ff7-prsmn`，Pod IP `10.42.0.32` |
| 控制台上游 jasagent-web（集群内） | `http://jasagent-web.jasagent.svc.cluster.local:18722`，ClusterIP `10.43.106.194`，Pod `jasagent-web-787f99576-p9d94`，Pod IP `10.42.0.23` |
| 本机访问网关 | `kubectl -n jasagent port-forward --address 127.0.0.1 svc/jasagent-gateway 18721:18721`，然后访问 `http://127.0.0.1:18721` |
| 发现到的 agent（官方探测当时） | `jasagent-agent-0` `10.42.0.43:8080`，`jasagent-agent-1` `10.42.0.42:8080`，`jasagent-agent-2` `10.42.0.41:8080`，`jasagent-agent-3` `10.42.0.40:8080`（均非回环，已在 Nacos 注册）。HPA 可能继续调整副本数 |
| 每 Pod 内 sidecar | `http://127.0.0.1:18761`。sidecar 容器镜像仍是 `jasagent-sidecar:0.1.0`（镜像 ID `sha256:25e6c2e0f5d8fa81fd7cf92aad818131dcbb9841a92b5586d986b812747e9bfa`），来自 021 的 [PR #9](https://github.com/xuhang113/JaSAgent/pull/9) `901361c`，本轮没有重建 sidecar |
| agent 容器镜像 | 滚动后均为 `jasagent-agent:0.1.0`（镜像 ID `sha256:6725bb4751258e144e742efc520eb0d2462e13a2eb0be6937e1a359e664f5bd9`），jar sha256 `66a3a6fc5acc4dcf33bfaf09075262ce8f46da8338917ad345cbe233ebe284c3`，来自 [PR #11](https://github.com/xuhang113/JaSAgent/pull/11) 的 `197ffe6` |
| Nacos | `http://jasagent-nacos.jasagent.svc.cluster.local:8848`，gRPC `9848`，ClusterIP `10.43.197.129` |
| agentpass | `http://jasagent-agentpass.jasagent.svc.cluster.local:18741`，ClusterIP `10.43.159.39` |
| rag | `http://jasagent-rag.jasagent.svc.cluster.local:18751`，ClusterIP `10.43.149.229` |
| mysql | `jasagent-mysql.jasagent.svc.cluster.local:3306`，ClusterIP `10.43.157.90`，Pod `jasagent-mysql-565b56b555-l5qjr`，Pod IP `10.42.0.6`。本轮没有删除或重建这个 Pod，uid 仍是 `5af2e226-a9ff-4d09-ae0c-e809a0dece1a` |
| redis | `jasagent-redis.jasagent.svc.cluster.local:6379`，ClusterIP `10.43.210.7`，Pod `jasagent-redis-679668c9f-lcjrd`，Pod IP `10.42.0.5` |
| Ingress 控制器 Pod | `ingress-nginx-controller-788bbd94df-jrxz6`，Pod IP `10.42.0.18` |
| 本机 Tailscale | 主机名 `jasagent-cloud`，稳定主机名 `jasagent-cloud.tail01f87a.ts.net`，IPv4 `100.69.17.4` |

## 本轮清单

本轮不需要给 agent 增加环境变量或启动参数。生产默认 `jasagent.app.tomcat-platform-workers=0` 已写在模块配置里，agent StatefulSet 没有设置这个键，也没有把 `server.tomcat.threads.max` 改低。`deploy/k8s` 相对当前 master 没有改动。把平台线程数压低只用于 ATDD 验收脚本，没有写进生产清单。

## 验收命令

在这台云电脑上，用 [PR #11](https://github.com/xuhang113/JaSAgent/pull/11) 的 `95db339`（产品实现 `197ffe6`）检出后实际执行过（只重建并滚动 agent 镜像，没有跑完整的 `scripts/up-stack.sh`，也没有滚动网关、sidecar 或重建 MySQL）：

```bash
mvn -pl jasagent-app -am -DskipTests package
# 按一键脚本的本地路径，用已有的 Ubuntu base 和 Temurin 21 JRE 构建 agent
docker build -f deploy/k8s/Dockerfile --build-arg JASAGENT_ROLE=agent --build-arg JASAGENT_PORT=8080 -t jasagent-agent:0.1.0 <构建上下文>
# 随后按一键脚本同样方式把镜像导入本机 k3s
kubectl -n jasagent rollout restart statefulset/jasagent-agent
kubectl -n jasagent rollout status statefulset/jasagent-agent
bash scripts/k8s/probe-egress.sh
kubectl -n jasagent port-forward --address 127.0.0.1 svc/jasagent-gateway 18721:18721
# 然后跑本机既有的 015 风格 I-001 至 I-016 运行器（不在仓库里）
```

本轮没有修改 `scripts/up-stack.sh`、`scripts/k8s/prepare-cluster.sh`、`scripts/k8s/configure-funnel.sh` 或 `scripts/k8s/probe-egress.sh`。验收前本机 Funnel 一度因 `tailscaled` 离线，从这台机器访问公网边缘出现 TLS unexpected EOF。恢复时只按原参数重启了已有状态的 `tailscaled`，并再次执行 `tailscale funnel --bg http://127.0.0.1:30080`。没有执行 `tailscale up` 或 `tailscale login`。

## 集成测试结果

### 官方 `scripts/k8s/probe-egress.sh`

通过。11:52:51（Asia/Shanghai）打印 `PROBE PASSED`，脚本退出码 0。结果包括：`PASS pods-ready`、`PASS runtime-class`、`PASS gvisor-discovered-agents`、`PASS gvisor-gateway`、`PASS gvisor-agentpass`、`PASS gvisor-sidecar`、`PASS gvisor-nacos`、网关与 agentpass 身份、发现到的 agent 均可访问，以及 018 的出站约定：`PASS agent-direct-external-blocked`、`PASS llm-deepseek-no-ticket`、`PASS llm-xai-no-ticket`、`PASS external-no-ticket-denied`、`PASS ticket-issued`、`PASS external-with-ticket`。019 / 020 入口检查也仍在：`PASS ingress-nginx-runtime`（控制器为 gVisor，且 `hostNetwork=false`）、`PASS ingress-only-gateway`（`jasagent-gateway:18721`）、`PASS forwarded-trusted-ingress-only`（`trusted=10.42.0.18/32`）、`PASS gateway-single-replica-web-origin`（`replicas=1 ready=1 origin=http://jasagent-web.jasagent.svc.cluster.local:18722`）。探测脚本相对仓库没有改动，egress、sidecar 线程与 gVisor 检查范围没有缩小。018 的每 Pod sidecar 与出站约定、021 的 sidecar 镜像与验收范围仍然有效。

### 015 风格 I-001 至 I-016

通过。11:53:03（Asia/Shanghai）使用本机既有运行器 `/tmp/jasagent-019-deploy/run-it-001-016.sh`（仓库里没有这份脚本）。I-001 至 I-016 全部为 `PASS`，摘要为 `016 IT PASSED`。网关仍是单副本；库 `jasagent` 里仍能看到 `jasagent_account`、`jasagent_login_session`、`jasagent_gateway_affinity` 和 `agent_session`；JDBC 地址不含 `password=`；网关与 web 的 `/proc/version` 均为 gVisor。滚动 agent 后，运维账号经网关登录仍然成功。

### 公网 Funnel

`GET https://jasagent-cloud.tail01f87a.ts.net/health` 在 11:53:28（Asia/Shanghai）返回 200，正文为 `{"status":"up"}`。Ingress 后端仍只有 `jasagent-gateway:18721`。

### 规格第 10 到第 12 条

| 验收 | 结果 | 说明 |
| --- | --- | --- |
| 10. 官方探测整体为 `PROBE PASSED`；egress、sidecar 线程与 gVisor 范围没有被缩小。018 / 021 在云电脑上已通过的集成检查仍然成立。网关仍是单副本。015 / 016 / 017 / 018 / 020 / 021 在云电脑上已通过的集成检查仍然成立 | 通过 | 官方探测于 11:52:51 为 `PROBE PASSED`。I-001 至 I-016 于 11:53:03 全部通过。网关仍是单副本。只滚动了 agent StatefulSet 以换上新的 agent 镜像，没有改 sidecar 镜像，也没有重建 MySQL。公网 `/health` 于 11:53:28 返回 200 |
| 11. 生产 `deploy/k8s` 的线程上限没有被永久改低。若本轮需要环境变量或启动参数，已由部署开 PR 调整，而不是直接推 master | 通过 | 本轮不需要改清单。生产默认不替换 Tomcat 线程池，agent 清单里没有 `jasagent.app.tomcat-platform-workers`，也没有把 `server.tomcat.threads.max` 改低。`deploy/k8s` 未改动，也没有直接推 master |
| 12. 本文件写有环境地址、命令和集成测试结果。验收环境是部署的云电脑 | 通过 | 见上文。本文件由部署开 Pull Request 提交 |

本轮没有把 022 整块宣布完成。等本文件所在 PR 经审查合进 master 后，再由领队宣布。
