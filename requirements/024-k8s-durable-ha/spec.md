# 需求 024 K8s 持久化与网关多副本

**状态**: 已确认

确认记录见同目录 `review.md`。2026-11-22（Asia/Shanghai）领队、调研、实现、测试、部署、代码review 均已通过，补充已并进口径。Hang 于 2026-11-22 约 10:08（Asia/Shanghai）确认「开始搞024」。023-agentpass-tenant-durable 已整块完成。对照确认时的产品 master [`e9db29e`](https://github.com/xuhang113/JaSAgent/commit/e9db29eac729e7ac3189b8777dc4f8923622b5a0)；领队随后写入的 023 `status.md` 在 [`a1471d1`](https://github.com/xuhang113/JaSAgent/commit/a1471d1773bfaf1e2f6791f7eed9092cf7bc7f7b)，未改产品代码。

## 这次要改什么

023 已经把通行证按租户持久化到现有 MySQL，产品已合进 master。现在的问题是：MySQL 数据目录仍是 `emptyDir`，删或重建 MySQL Pod 会丢掉库 `jasagent` 里的 020 会话和 023 证与流水；网关仍是单副本，官方探测还断言必须单副本，清单仍写着存活探测在进程内；公网 Funnel 在云电脑更新后尚未恢复，019 要求的集群外或手机登录、EventSource，以及 `/admin/cluster` 和未登录拦截，都还没正式确认；官方 `prepare-cluster.sh` 仍只走 systemd，而这台验收云电脑没有可用的 systemd。

这次改成这几件事。

1. 先把现有 `jasagent` 库迁到持久卷再切换，不能直接用空卷滚动掉当前 MySQL Pod。迁完之后，删/重建该 Pod 不得丢数据。不新建库。整机或数据盘被清空不在本轮承诺里。
2. 网关至少 2 个就绪副本。若存活探测或 EventSource 仍依赖单进程状态、会挡住多副本，由实现先改 `jasagent-gateway` 做成跨副本可用，不能只改副本数。
3. 官方探测改掉「必须单副本」的断言，改为至少 2 个就绪副本，web 上游仍是集群内地址。官方探测仍须 `PROBE PASSED`，018–023 的检查范围不得缩小。
4. 恢复 Funnel 公网路径（`*.ts.net` → Ingress-nginx → 只到 `jasagent-gateway:18721`），正式确认集群外或手机可登录、控制台 EventSource 经该 HTTPS 入口可用，并补上 019 尚未正式关闭的 `/admin/cluster` 和未登录拦截。
5. 把已验证的无 systemd 进程拉起方式收进官方 `prepare-cluster.sh`。有 systemd 的机器仍走原路径。
6. 有网关代码就先由实现开 PR（不动 `deploy/k8s`），代码审查、测试通过后，再由部署开 PR。若无需改网关代码，则由部署开 PR。不直接推 master。

界面仍只用 `jasagent-web`。不改 sidecar 和 agentpass 的产品约定。不把 web、Nacos、MySQL、Redis、agent 或 agentpass 暴露到公网。验收以部署的云电脑为准。每会话关注点和技能自动摘要、日程推送、多媒体、外部插件、CodeReview、Nacos 集群化和 Redis 持久化不在本轮。

## 模块

| 模块 | 现在负责什么 | 这次改什么 |
| --- | --- | --- |
| deploy/k8s | MySQL 数据目录是 `emptyDir`；网关 `replicas: 1`，`JASAGENT_GATEWAY_REPLICAS=1`；Ingress 只转到网关；agentpass 为 ClusterIP | 为已有库 `jasagent` 换成持久卷，并先迁现有数据再切卷。网关至少 2 个就绪副本。Ingress 仍只转到 `jasagent-gateway:18721`，不把 web、Nacos、MySQL、Redis、agent 或 agentpass 挂到公网入口。实现阶段不动本目录 |
| scripts/k8s/prepare-cluster.sh | 仍走 systemd；这台云电脑没有可用 systemd 时，不能单独把集群装完 | 没有可用 systemd 时走已验证的进程拉起（含 `--flannel-backend=host-gw`、`--disable=traefik`，以及预先写入 runsc 模板）；有 systemd 的机器仍走原路径 |
| scripts/k8s/probe-egress.sh | `gateway-single-replica-web-origin` 要求网关副本为 1，web 上游为集群内地址 | 改成至少 2 个就绪副本，web 上游仍是集群内地址。整体仍须 `PROBE PASSED`，018–023 的其余检查范围不得缩小，不得跳过既有项来凑通过 |
| 官方一键拉起与 Funnel 配置脚本 | 一键拉起栈；Funnel 配置已有，但云电脑更新后 Funnel 状态不在 | 按本轮持久卷和网关多副本调整拉起步骤。恢复 Funnel 公网路径。凭证不进 Git。若要重新登录，只在私信里找 Hang，不在群里执行 `tailscale up` |
| jasagent-gateway | 对外入口；020 后账号、登录会话、粘性已在 MySQL；清单仍写着存活探测在进程内；`JASAGENT_GATEWAY_FORWARDED_TRUSTED` 只信任 Ingress | 仅当存活探测或 EventSource 仍依赖单进程状态、会挡住多副本时接线修改，做成跨副本可用。跨副本仍须满足 020 的 Cookie 与租户隔离，非 admin 不能打到集群接口或他人会话。`JASAGENT_GATEWAY_FORWARDED_TRUSTED` 仍只能是 Ingress，禁止 `0.0.0.0/0` 或 `::/0`。实现开 PR 时不动 `deploy/k8s` |
| jasagent-web | 只提供页面，由网关反向代理 | 本轮不改。界面仍只放在这里 |
| jasagent-sidecar / jasagent-agentpass | 018–023 已锁定的出站、票和租户持久化约定 | 不改产品约定 |

## 改动地方

**deploy/k8s**

实现和测试提交不得修改这个目录。MySQL 仍是栈里已有的那一个库 `jasagent`，不另建库。数据目录从 `emptyDir` 改成持久卷。切换时必须先把现有库迁过去，不能直接用空卷滚动掉当前 MySQL Pod。迁完后，删掉或重建该 Pod，同一库里的账号、登录会话、粘性表、聊天会话、023 的证和流水都还在。整机或数据盘被清空不在本轮承诺里。

网关 Deployment 至少 2 个就绪副本，相应环境变量不得再写成单副本。Ingress 仍只转到 `jasagent-gateway:18721`。不得把 `jasagent-web`、Nacos、MySQL、Redis、agent、agentpass 或其它非网关组件挂到这条公网入口上。`JASAGENT_GATEWAY_WEB_ORIGIN` 仍指向集群内 `jasagent-web`，不得改成公网主机名。`JASAGENT_GATEWAY_FORWARDED_TRUSTED` 仍只信任 Ingress 这一跳，禁止 `0.0.0.0/0` 或 `::/0`。

**官方脚本**

`scripts/k8s/prepare-cluster.sh` 必须能在没有可用 systemd 的云电脑上把 k3s 和 gVisor 拉起来：走已验证的进程方式，参数包括 `--flannel-backend=host-gw`、`--disable=traefik`，并预先写入 runsc 模板。有 systemd 的机器仍走原路径。

`scripts/k8s/probe-egress.sh` 把 `gateway-single-replica-web-origin` 改为检查至少 2 个就绪副本，并保持 web 上游是集群内地址。脚本整体仍须打印 `PROBE PASSED`。018–023 已有的 egress、sidecar 线程、运行时异步、gVisor、入口只到网关、转发头只信任 Ingress、以及 023 的 `ticket-issued` / `external-with-ticket` 等检查范围不得缩小，不得删项或跳过。

恢复 Funnel 时，路径仍是稳定的 `*.ts.net` HTTPS 主机名 → Funnel → Ingress-nginx → 只转到 `jasagent-gateway:18721`。Funnel 与 Ingress 不得整包缓冲，控制台 EventSource 经该入口可用。凭证只放集群 Secret 或仓库外配置，不进 Git。若创建或恢复 Funnel 需要 Hang 的账号或登录，向 Hang 私信办理，不在群里执行 `tailscale up`。

**jasagent-gateway**

仅当存活探测或 EventSource 仍依赖单进程状态、只改副本数会出错时，才由实现改这个模块。要做成跨副本可用：同一用户的会话 Cookie 在任一就绪副本上仍有效；租户隔离与 020 相同，非 admin 不能打到集群接口或他人会话。`JASAGENT_GATEWAY_FORWARDED_TRUSTED` 的信任范围不变。本轮改到的类、常量、方法都要有注释。实现提交不动 `deploy/k8s`。

**jasagent-web、sidecar、agentpass**

本轮不改 `jasagent-web`。不改 sidecar 和 agentpass 的产品约定。018 出站、021 sidecar 线程、022 运行时异步、023 通行证按租户持久化的验收保持有效。

## 数据库

不新建库。仍使用已有库 `jasagent`。切持久卷前必须先迁现有数据。口令、令牌、密钥仍只从仓库外文件或 Secret 读取，JDBC 地址仍不能带 `password=`。

## 中间件

本轮不引进新的消息队列或其它业务中间件。不把 Nacos 改成集群，也不给 Redis 做持久化。云电脑继续使用 k3s，业务容器（除现有约定的 MySQL、Redis 外）使用 gVisor。公网入口仍用 Tailscale Funnel 的 `*.ts.net`，不实施 Cloudflare Tunnel，也不使用临时 trycloudflare 链接。

## 开发规范

本轮改到的模块必须同时满足：新增和改动的类、常量、方法都有注释；有对应的测试代码；有可执行的测试脚本。口令、内部令牌、Funnel/Tailscale 凭证和密钥不写进仓库、日志、异常或响应，也不发到群里。有网关代码时，实现和测试阶段不改 `deploy/k8s`；ATDD 全部通过后，由部署补持久卷、网关多副本、探测、集群准备脚本和 Funnel 恢复，并交出带环境地址、命令和集成测试结果的 `deploy.md`。若无需改网关代码，则这些清单和脚本改动由部署直接开 PR。变更记录不能代替本轮 ATDD。验收环境以部署的云电脑为准，不再依赖 Hang 的 Mac。

## 测试要求

按下面几条验收。每条单独做，做完就能判断过不过。变更记录不能代替这些验收。实现提交后由测试角色复跑，并把结果写入 `results/`。其中在仓库内就能判定的合同项，在 ATDD 阶段完成；必须在云电脑集群上观察的项，在部署阶段由部署执行，并写入 `deploy.md`。若本轮无需改网关代码，则第 1 到第 6 条中依赖网关代码的项并入部署阶段，仍须可判定过不过，不能缺项。

1. 跨副本下，020 的会话 Cookie 与租户隔离仍然成立：同一用户的登录会话在任一就绪网关副本上可用；租户 A 不能读到或操作租户 B 的会话。
2. 非 admin 不能打到集群接口或他人会话。
3. 存活探测和 EventSource 不依赖单进程状态，网关至少 2 个就绪副本时仍然可用。
4. `JASAGENT_GATEWAY_FORWARDED_TRUSTED` 仍只信任 Ingress，取值禁止 `0.0.0.0/0` 或 `::/0`。任意客户端自己带来的 `X-Forwarded-*` 不被信任。
5. 不改 sidecar 和 agentpass 的产品约定。`/v1/tickets` 的领证、校验、核销约定不变。018 / 021 / 022 / 023 仍须成立。
6. 实现若改 `jasagent-gateway`，只改该模块及必要接线，不修改 `deploy/k8s`。本轮改到的模块有注释，有自动测试，并且有可执行的测试脚本。口令、令牌、凭证和密钥不出现在仓库、说明或日志里。

第 1 到第 6 条在存在网关代码时于 ATDD 阶段验收。下面几条在部署阶段验收。

7. 已先把现有 `jasagent` 库迁到持久卷再切换，没有用空卷滚动掉原 Pod。删掉或重建 MySQL Pod 之后，同一库里的 020 会话和 023 证与流水都还在。不新建库。
8. 网关至少 2 个就绪副本。官方 `scripts/k8s/probe-egress.sh` 整体为 `PROBE PASSED`；原 `gateway-single-replica-web-origin` 已改为至少 2 个就绪副本，且 web 上游仍是集群内地址。018–023 的其余检查范围没有被缩小，也没有跳过既有项。015 / 016 / 017 / 018 / 020 / 021 / 022 / 023 在云电脑上已通过的集成检查仍然成立。
9. 公网路径为 `*.ts.net` → Funnel → Ingress-nginx → 只转到 `jasagent-gateway:18721`。不暴露 web、Nacos、MySQL、Redis、agent 或 agentpass。Hang 在集群外（或手机）走一遍后，可以打开控制台并完成登录；控制台 EventSource 经该 HTTPS 入口可用；019 尚未正式关闭的 `/admin/cluster` 和未登录拦截成立。集群外登录不能只在集群内代替。
10. 官方 `scripts/k8s/prepare-cluster.sh` 在没有可用 systemd 时能按已验证的进程方式把集群拉起来（含 `--flannel-backend=host-gw`、`--disable=traefik`，以及预先写入 runsc 模板）；有 systemd 的机器仍走原路径。
11. 清单和脚本改动已由部署开 PR 提交，而不是直接推 master。Funnel 和 Tailscale 凭证不在 Git、日志或说明里。若恢复 Funnel 需要重新登录，只在私信里找 Hang。
12. `requirements/024-k8s-durable-ha/deploy.md` 写有环境地址、命令和集成测试结果。验收环境是部署的云电脑，不再依赖 Hang 的 Mac。

## 还没定

没有还没定的口径。持久卷的具体存储类、路径和迁移步骤，由部署在「先迁现有库、删/重建 MySQL Pod 不丢数据、不新建库」的约束内决定。若实现核对后确认存活探测和 EventSource 已不依赖单进程状态，可以不改 `jasagent-gateway`，但仍须达到至少 2 个就绪副本且上述验收成立。每会话关注点和技能自动摘要、日程推送、多媒体、外部插件、CodeReview、Nacos 集群化、Redis 持久化，以及整机或数据盘被清空后的恢复，不在本轮。
