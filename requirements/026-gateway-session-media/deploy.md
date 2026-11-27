# 部署记录 026-gateway-session-media

**时间**：2026-11-27（Asia/Shanghai）。本文件写规格第 9 到第 12 条在部署云电脑上的结果。验收环境是这台云电脑，不再依赖 Hang 的 Mac。口令、令牌、Funnel/Tailscale 凭证、Authorization、Cookie 和密钥原文不写入本文件。

ATDD 第 1 到第 8 条已在 [PR #19](https://github.com/xuhang113/JaSAgent/pull/19) 通过，头提交是 [`6d3afbe`](https://github.com/xuhang113/JaSAgent/commit/6d3afbe2c61da97607bbddc03fb9b98fc411e772)（2026-11-27 15:57 Asia/Shanghai），产品实现基线是 [`35ac67bb`](https://github.com/xuhang113/JaSAgent/commit/35ac67bb1e8e710e5abd86e6a75ca048b4384154)。领队已独立核对 T-001 至 T-008 均为通过，并与已确认 spec 第 1–8 条对得上。该实现 PR 现在仍不合入。本分支从当时的 #19 头提交另开，只增加 `deploy/k8s` 和本记录，不改产品代码、测试或官方集群脚本顺序，也不直接推 master。

本轮是网关会话多媒体。附件由网关自己处理，优先写入已有 MySQL 库 `jasagent` 的 `session_attachment`，不新建库。Funnel / Tailscale 不在本轮，不恢复，也不把公网入口或历史主机名写成可用。

## 环境

（集群拉起后回写实测节点、副本、地址。本轮验收前云电脑上的 k3s、`/var/lib/rancher`、`/etc/rancher` 和 `/var/lib/docker` 均不在，需要按官方脚本重新准备集群。）

## 本轮清单

- `deploy/k8s/gateway.yaml`：注释写明附件表 `session_attachment` 与账号、登录会话共用已有 MySQL，以及网关 `home` emptyDir 不是附件存储，也不能把两个副本挂到同一块普通 RWO 盘。副本数仍是 2，JDBC 环境变量不变，不增加附件卷。
- `deploy/k8s/mysql.yaml`：注释写明同一库承载附件；为 50 MiB 视频正文增加 `--max-allowed-packet=104857600`。仍是已有的那一个 MySQL 实例和 PVC，不另建库。
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

全新集群上官方脚本若先写 Secret 再应用清单，会因命名空间 `jasagent` 还不存在而失败。本轮不改官方脚本的这一顺序，先执行 `kubectl apply -f deploy/k8s/namespace.yaml`，再跑官方 `up-stack.sh`。没有执行 `tailscale up` 或 `tailscale login`。没有跳过 `ticket-issued` 或 `external-with-ticket`。官方探测须整体为 `PROBE PASSED`，018–025 既有项不缩小。

## 环境地址

（待集群拉起后回写。若 NodePort 30080 仍因缺少 `xt_statistic` 不通，据实记录，不把公网入口写成可用，也不据此缩小官方探测。）

## 集成测试结果

（待官方探测和第 9–12 条走完后回写，含 Asia/Shanghai 时间与提交 SHA。）
