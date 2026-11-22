# 部署记录 024-k8s-durable-ha

**时间**：2026-11-22（Asia/Shanghai）。本文件写规格第 7 到第 12 条在部署云电脑上的结果。验收环境是这台云电脑，不再依赖 Hang 的 Mac。口令、令牌、Funnel/Tailscale 凭证和密钥原文不写入本文件。

ATDD 第 1 到第 6 条已在 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15) 的 [`33a1c4bb`](https://github.com/xuhang113/JaSAgent/commit/33a1c4bbca339d201c70bb7443954b16eee10b3b) 通过，产品实现提交是 [`c42fdda8`](https://github.com/xuhang113/JaSAgent/commit/c42fdda8d4f69accea112480443d0d569fbb377d)，结果在 `requirements/024-k8s-durable-ha/results/`。本 PR 从 master [`390c4c8`](https://github.com/xuhang113/JaSAgent/commit/390c4c82861726b2d58500c4f38b00647bcddf06) 另开，只含 `deploy/k8s`、官方脚本和本记录，不改 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15) 里的产品代码，也没有直接推 master。

2026-11-21 云电脑更新后，本机 k3s、Docker 和 `/var/lib/tailscale` 都不在了。原来的 `jasagent` 库随 emptyDir 和数据盘一起丢失。整机或数据盘清空不在本轮承诺里。验收按领队确认的路径：先在当前 MySQL（emptyDir）写入 020 会话和 023 通行证/审计，再迁到持久卷，然后删/重建 MySQL Pod，这些数据必须还在。没有把已经写好的库直接滚到空卷上，也没有另建库。

## 环境

- 节点：`grok-bot-vm-209170388`，Internal-IP `172.30.0.2`，Ready。Kubernetes `v1.31.4+k3s1`，containerd `1.7.23-k3s2`。PID 1 是 tini，没有可用的 systemd。内核 `6.12.113+`，系统 Debian GNU/Linux 13 (trixie)
- 官方 `scripts/k8s/prepare-cluster.sh` 于 13:15:08（Asia/Shanghai）以进程方式拉起 k3s。日志写明 `Starting flannel with backend host-gw`。RuntimeClass `gvisor`（handler=`runsc`）于 13:15:36（Asia/Shanghai）创建。第一次执行时节点已经就绪，但生成后的 containerd 配置还要再写一会儿，脚本当时报「重启后 k3s containerd 仍没有登记 runsc」；随后同一份脚本重跑成功。本 PR 在 `configure_containerd` 里补了 `wait_for_containerd_runsc`（最多等 30 次、每次 2 秒），避免这次竞态。有 systemd 的机器仍走原来的官方安装脚本
- 命名空间：`jasagent`（业务），`ingress-nginx`（入口控制器）
- 网关 Deployment `spec.replicas=2`，就绪副本 2，`JASAGENT_GATEWAY_REPLICAS=2`。`JASAGENT_GATEWAY_WEB_ORIGIN=http://jasagent-web.jasagent.svc.cluster.local:18722`
- `JASAGENT_GATEWAY_FORWARDED_TRUSTED` 只信任 Ingress 这一跳 `10.42.0.24/32`，不是 `0.0.0.0/0` 或 `::/0`
- 公网路径仍应是 `*.ts.net` → Tailscale Funnel → Ingress-nginx NodePort `30080` → 只转到 `jasagent-gateway:18721`。更新后 `/var/lib/tailscale` 仍不在，`tailscaled` 未运行。本轮没有执行 `tailscale up` 或 `tailscale login`。若要重新登录，只在与 Hang 的私信里办理
- Ingress 后端仍只有 `jasagent-gateway:18721`。没有把 web、Nacos、MySQL、Redis、agent 或 agentpass 挂到公网入口。agentpass 仍是 ClusterIP `10.43.215.11:18741`
- MySQL 数据目录已切换为 PersistentVolumeClaim `jasagent-mysql-data`（StorageClass `local-path`，10Gi，RWO，卷 `pvc-e9675a2f-b57c-42cc-b5a7-e8eda1f133a7`）。该卷能挺过 Pod 删除，挺不过整机或 `/var/lib/rancher` 被清空
- 业务镜像用 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15) 头提交 `33a1c4bb`（产品提交 `c42fdda8`）打包，清单和官方脚本用本 PR。没有把产品代码提交到本分支

## 环境地址

| 服务 | 地址 |
| --- | --- |
| 公网入口 | 历史主机名是 `https://jasagent-cloud.tail01f87a.ts.net`。本轮 Funnel 状态不在，公网 `/health` 未复查 |
| Ingress-nginx Service | NodePort `10.43.170.185`，`80:30080/TCP`、`443:30443/TCP`。控制器 Pod `10.42.0.24`。官方探测的入口检查已通过。本轮从宿主机访问 `127.0.0.1:30080` 和 `172.30.0.2:30080` 未打通（30080 未在宿主机监听），不能代替集群外验收 |
| 网关（集群内） | Service `10.43.212.22:18721`，`http://jasagent-gateway.jasagent.svc.cluster.local:18721`。就绪 Pod `10.42.0.25`、`10.42.0.26` |
| 控制台上游 jasagent-web（集群内） | Service `10.43.128.40:18722`，`http://jasagent-web.jasagent.svc.cluster.local:18722` |
| mysql | `jasagent-mysql.jasagent.svc.cluster.local:3306`（`10.43.182.81`），数据目录使用 PVC `jasagent-mysql-data`。当前 Pod `jasagent-mysql-6dc59b87c7-tpb98`，IP `10.42.0.10` |
| agentpass | ClusterIP `10.43.215.11:18741`，不暴露到公网 |
| redis / nacos | Redis `10.43.175.106:6379`；Nacos `10.43.94.50:8848,9848`，均为 ClusterIP |

## 本轮清单和脚本

- `deploy/k8s/mysql.yaml`：增加 PVC `jasagent-mysql-data`，Deployment 不再使用 emptyDir
- `deploy/k8s/gateway.yaml`：副本改为 2，环境变量同步为 2，注释改为会话和存活探测在已有 MySQL 库
- `scripts/up-stack.sh`：apply `mysql.yaml` 之前，若当前 MySQL 仍不是目标 PVC 且库 `jasagent` 已有表，先把 `/var/lib/mysql` 拷到持久卷，再切换 Deployment。也可单独执行 `bash scripts/up-stack.sh --migrate-mysql`
- `scripts/k8s/probe-egress.sh`：将单副本断言改为至少 2 个就绪副本，web 上游不变。018–023 的其余检查（含 `ticket-issued` / `external-with-ticket`、入口只到网关、转发头只信任 Ingress）没有删除或跳过
- `scripts/k8s/prepare-cluster.sh`：无 systemd 时走进程拉起，并预先写入 runsc 模板。本轮补了等待 containerd 登记 runsc 的重试，避免进程刚拉起时的竞态

没有改 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15) 的产品代码。凭证不进 Git。

## 验收命令

```bash
# 1. 用官方脚本在无 systemd 的云电脑上准备 k3s 和 gVisor
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
bash scripts/k8s/prepare-cluster.sh

# 2. 先按原 emptyDir 拉起 MySQL，写入 020 会话和 023 通行证/审计（不另建库）
# 3. 再执行官方迁移，切到持久卷；不要直接用空卷滚动掉已有库的 Pod
bash scripts/up-stack.sh --migrate-mysql

# 4. 删除或重建 MySQL Pod，核对自己刚写入的会话和证还在
# 5. 用 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15) 的产品提交构建网关镜像后执行官方一键脚本
#    网关至少 2 个就绪副本
bash scripts/up-stack.sh
bash scripts/k8s/probe-egress.sh
```

没有执行 `tailscale up` 或 `tailscale login`。没有跳过 `ticket-issued` 或 `external-with-ticket`。

## 集成测试结果

### 第 7 条：先迁现有库再切持久卷，删/重建 Pod 后数据还在

通过。先在 emptyDir 的 MySQL 里、同一库 `jasagent` 写入标记行：账号 `deploy024` / 租户 `tenant-024-cutover`，会话 `sess-024-cutover`，通行证 `ticket-024-cutover`，审计 `audit-024-cutover`。随后执行官方 `bash scripts/up-stack.sh --migrate-mysql`：先确认 PVC `jasagent-mysql-data`，用辅助 Pod `jasagent-mysql-migrate` 把当时 Pod `jasagent-mysql-565b56b555-pbrpv` 的 `/var/lib/mysql` 拷到持久卷，再切换 Deployment，退出码 0。切换后卷已是 `persistentVolumeClaim.claimName=jasagent-mysql-data`。删除新副本 `jasagent-mysql-6dc59b87c7-hzh2r` 后，重建出的 `jasagent-mysql-6dc59b87c7-tpb98` 于 13:19:46（Asia/Shanghai）就绪，上述四条标记行都还在。没有另建库，也没有把带数据的 emptyDir Pod 直接滚到空卷上。

### 官方 `scripts/k8s/probe-egress.sh`

通过。官方 `bash scripts/up-stack.sh` 拉起整栈后，第一次随部署在 13:26:29（Asia/Shanghai）左右结束，结果是 `PROBE FAILED`，失败项只有 `ticket-issued` 和随后的 `external-with-ticket`，其余项已通过。原因与 023 相同：官方探测仍按文件会话准备用户 `stack` / 会话 `s1`，023 发证只认集群 MySQL 里的会话和策略。本轮没有改探测脚本，也没有跳过这两项。在同一库 `jasagent` 写入探测用登录会话和 `net.egress` 允许策略后，于 13:28:05（Asia/Shanghai）重跑，脚本退出码 0，打印 `PROBE PASSED`。

结果包括：`PASS pods-ready`、`PASS runtime-class`、`PASS gvisor-discovered-agents`、`PASS gvisor-gateway`、`PASS gvisor-agentpass`、`PASS gvisor-sidecar`、`PASS gvisor-nacos`、网关与 agentpass 身份、发现到的 agent 均可访问，以及 018 的出站约定：`PASS agent-direct-external-blocked`、`PASS llm-deepseek-no-ticket`、`PASS llm-xai-no-ticket`、`PASS external-no-ticket-denied`、`PASS ticket-issued`、`PASS external-with-ticket`。019 / 020 入口检查也仍在：`PASS ingress-nginx-runtime`、`PASS ingress-only-gateway`（`jasagent-gateway:18721`）、`PASS forwarded-trusted-ingress-only`（`trusted=10.42.0.24/32`）、`PASS gateway-multi-replica-web-origin`（`replicas=2 ready=2 origin=http://jasagent-web.jasagent.svc.cluster.local:18722`）。egress、sidecar 线程、运行时异步与 gVisor 检查范围没有缩小。018 / 021 / 022 / 023 的验收范围仍然有效。

### 015 风格 I-001 至 I-016

通过。13:28:47（Asia/Shanghai）使用本机运行器 `/tmp/jasagent-024-deploy/run-it-001-016.sh`（仓库里没有这份脚本；沿用 015 口径，并将 I-001 按本轮锁定口径改为至少 2 个就绪副本）。I-001 至 I-016 全部为 `PASS`，摘要为 `016 IT PASSED`。其中：I-001 为 `replicas=2 ready=2 running=2`，容器只有 `gateway`；I-003 为集群内 web 上游；I-004 JDBC 指向集群内 MySQL 且地址不含 `password=`；I-005 全新库起初没有 `jasagent_gateway_affinity`，按 `MysqlAffinityStore.SCHEMA` 建表后复查通过；I-007 `/health` 为 200 且正文含 `"status":"up"`、不含 `"storage"`；I-009 未登录访问 `/admin/` 返回 302 到 `/admin/login`；I-010 / I-011 未登录返回 401，`errorCode` 为 `AUTH`；I-014 / I-015 的 web 与网关 `/proc/version` 均为 gVisor；I-016 本机 `127.0.0.1:18721` 上 `/health` 和 `/admin/login` 均为 200。

### 第 9 条：Funnel 与集群外登录

未复查。2026-11-21 更新后 `/var/lib/tailscale` 不在了，本轮没有执行 `tailscale up` 或 `tailscale login`。集群外或手机登录、控制台 EventSource，以及 019 尚未正式关闭的 `/admin/cluster` 和未登录拦截，不能只在集群内代替。若要恢复 Funnel，只在与 Hang 的私信里办理。

### 第 10 条：无 systemd 时官方 prepare-cluster.sh 能拉起集群

通过。本 PR 中的 `scripts/k8s/prepare-cluster.sh` 在这台没有可用 systemd 的云电脑上，以进程方式拉起 k3s `v1.31.4+k3s1`，日志为 `Starting flannel with backend host-gw`，并预先写入 runsc 模板。RuntimeClass `gvisor` 已创建。本轮补交了等待 containerd 登记 runsc 的重试。有 systemd 的路径没有改。

### 第 11 条：清单和脚本走 PR

本文件所在分支从 master `390c4c8` 另开，不直接推 master，也不改 [PR #15](https://github.com/xuhang113/JaSAgent/pull/15)。Funnel 和 Tailscale 凭证不在 Git、日志或本文件里。

### 第 12 条：本文件

已写环境地址、实际执行的命令，以及第 7、8、10 条和 I-001 至 I-016 的实测结果。第 9 条（Funnel 与集群外登录）仍未复查，024 整块不能算完成。
