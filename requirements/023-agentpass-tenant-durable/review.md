# 需求 023 通行证多租户持久化评审记录

- 日期：2026-11-20（Asia/Shanghai）
- 块：agentpass（`jasagent-agentpass`、`jasagent-agentpass-server`、`jasagent-agentpass-starter`，以及接线所需的 `jasagent-api` / `jasagent-auth` / `jasagent-persist`；要把发证前的会话和权限改走 020 的 MySQL，以及 PENDING 挂起、控制台审批后用原 executionId 继续，已经在这条链上的模块也可以改，尤其是 `jasagent-app`、`jasagent-controller` 和 `jasagent-web`。对应测试和脚本；`deploy/k8s` 仅在 ATDD 之后如需调整再由部署走 PR）
- 结论：已确认。领队、调研、实现、测试、部署、代码review 均写「通过」，补充已并进口径。Hang 于 2026-11-20 约 08:21（Asia/Shanghai）确认「通行证需要持久化权限判断 开始做吧」。本文件和 `spec.md` 在确认之后写成。022-runtime-full-async 已整块完成：实现 [PR #11](https://github.com/xuhang113/JaSAgent/pull/11)、部署 [PR #12](https://github.com/xuhang113/JaSAgent/pull/12) 已按顺序合入，产品代码基准为 [`19d1629`](https://github.com/xuhang113/JaSAgent/commit/19d16294100a223776b7377d1431423e4d0007eb)。之后领队写入 022 的 `status.md`，当时 master 为 [`9fa78a4`](https://github.com/xuhang113/JaSAgent/commit/9fa78a4469d6d3c6943e310f705349c759741062)，未改产品代码。

## 候选

调研在群里交出的候选 **023-agentpass-tenant-durable**。对照当时的产品 `master`（[`19d1629`](https://github.com/xuhang113/JaSAgent/commit/19d16294100a223776b7377d1431423e4d0007eb)）：005 已有进程内 `PassGate`（读用户目录 `auth/policies.yaml`，未命中则 PENDING）；015 后又有 `jasagent-agentpass-server` 的一次性票（`POST /v1/tickets` 领证、校验、核销），018 出站已走这套票。缺口是这套服务还停在本机文件和进程内存上，对不上已经落地的 C 端多租户。`HumanAccess` 仍去 `家目录/登录名/jasagent/auth/sessions` 对会话文件，`PolicyPassGate` 仍读同目录的 `auth/policies.yaml`。020 已把账号和登录会话迁到 MySQL，一人一租户，但 agentpass 的 home 在清单里还是 emptyDir，这些文件不是真相来源。`PassStore` 和 `AuditLog` 都是进程内集合，重启就丢已核销记录，已用的证可能被重放；清单未挂 `JASAGENT_AGENTPASS_SIGNING_KEY`，缺省是每次启动在内存里另生成一把。`GET /v1/tickets/audit` 无鉴权返回全部流水。领证遇到 PENDING 只回 403，不记待批、不挂起执行，控制台也没有审批；005「同一用户批准后用原 executionId 继续」只还在本机文件和 CLI 上。进程内门和一次性票两套并存，策略和审批没有按租户打通。

建议改动：本轮只改 agentpass 及相关模块：`jasagent-agentpass`、`jasagent-agentpass-server`、`jasagent-agentpass-starter`，接线所需可改 `jasagent-api`、`jasagent-auth`、`jasagent-persist`；审批界面只放 `jasagent-web`，并经网关访问，不把 agentpass 暴露到公网。不改 sidecar，不改 `/v1/tickets` 的领证、校验、核销约定，也不改 018 / 021 / 022 已锁定的出站和异步验收。实现和测试不改 `deploy/k8s`。要做到：发证前的会话、租户和权限以 020 的 MySQL 为准；证状态和流水落到已有 MySQL，Pod 或进程重启后已核销的证仍须拒绝重放；租户 A 不能领、验、核销 B 的证，流水不得对外倾倒或串租户；未命中策略走 PENDING，同一用户可在控制台批准或拒绝，批准后用原来的 executionId 继续，他人不能代批；生产签名密钥只进环境变量或 Secret，不进 Git 和日志，集群内 Service 必须能访问到服务。清单调整由部署在 ATDD 后开 PR。官方探测仍须 `PROBE PASSED`。流程仍是实现开 PR → 代码审查 → 测试 → 部署开 PR，不直接推 master。每会话 skill 总结、日程推送、多媒体、外部插件、CodeReview、网关高可用和整块 K8s 不在本轮。

候选交出后，部署先写「通过」。实现写「通过」，并补充三点：批准后用原 executionId 继续，接线必要时应包括运行时里已有的 resume 路径（`jasagent-app` / `jasagent-controller`）；生产缺少 `JASAGENT_AGENTPASS_SIGNING_KEY` 时不得再静默在内存里另生成一把，本地和验收可另给测试密钥；`GET /v1/tickets/audit` 必须鉴权并按租户过滤，策略也不再把 emptyDir 里的 `policies.yaml` 当真相来源。调研同意这三点并入候选口径。测试写「通过」，并锁可测项：不同租户须并发覆盖；未鉴权读 audit 应 401 或 403，已登录只看本租户；用同一库和同一签名密钥重启 agentpass 后，已核销的证仍须拒绝重放；未命中须留下待批，同一用户经 `jasagent-web`、走网关批准或拒绝，批准后用原来的 executionId 继续完成该次调用，他人代批须被拒绝，不能只测领证 403；策略和待批不能再只靠 emptyDir 上的文件。测试随后对上述三点再写「通过」。领队核对现状属实并写「通过」，补一句：接线不限于 api、auth、persist，已经在这条链上的模块也可以改，尤其是 `jasagent-app`、`jasagent-controller` 和 `jasagent-web`。代码review 写「通过」，确认实现补充、测试可测项和领队接线范围并入后没有新的反对项。部署和实现对并入后的口径再写「通过」。领队向 Hang 整理口径后请确认开工。Hang 于 2026-11-20 约 08:21 确认「通行证需要持久化权限判断 开始做吧」。

## 表态

- 领队：通过。对照产品 master [`19d1629`](https://github.com/xuhang113/JaSAgent/commit/19d16294100a223776b7377d1431423e4d0007eb) 核对过，现状属实。口径补一句：接线不限于 api、auth、persist。要把发证前的会话和权限改走 020 的 MySQL，以及 PENDING 挂起、控制台审批后用原 executionId 继续，已经在这条链上的模块也可以改，尤其是 `jasagent-app`、`jasagent-controller` 和 `jasagent-web`。不改 sidecar，不把 agentpass 暴露到公网，不改 `/v1/tickets` 的领证、校验、核销约定，也不改 018、021、022 的验收。实现和测试仍不动 `deploy/k8s`。并将实现补充的三点、测试锁定的可测项并进口径。确认前不写 SDD、不改代码、不部署。
- 调研：交出候选 023-agentpass-tenant-durable，并同意上述锁定口径和补充。确认前不写文件、不改代码。
- 实现：通过。缺口和范围同意。补充：批准后用原 executionId 继续，接线必要时应包括运行时里已有的 resume 路径（`jasagent-app` / `jasagent-controller`）；生产缺少 `JASAGENT_AGENTPASS_SIGNING_KEY` 时不得再静默在内存里另生成一把，本地和验收可另给测试密钥，生产密钥只进环境变量或 Secret，不进 Git 和日志；`GET /v1/tickets/audit` 必须鉴权并按租户过滤，策略也不再把 emptyDir 里的 `policies.yaml` 当真相来源。后对领队补的接线范围和测试锁的可测项再写「通过」。确认前不写文件、不改代码。
- 测试：通过。可测口径：不同租户须并发覆盖，A 不能领、验、核销 B 的证，流水按租户隔离；`GET /v1/tickets/audit` 未鉴权应 401 或 403，不得再回全部流水，已登录也只能看本租户。`/v1/tickets` 的领证、校验、核销约定不变，018 / 021 / 022 仍须成立。证状态进已有 MySQL 后，ATDD 不改 `deploy/k8s`，用同一库和同一签名密钥环境变量重启 agentpass 进程，已核销的证仍须拒绝重放。密钥只在运行时注入，不进仓库、说明或日志。未命中须留下待批，同一用户经 `jasagent-web`、走网关批准或拒绝，批准后用原来的 `executionId` 继续完成该次调用，他人代批须被拒绝，不能只测领证 403。策略和待批不能再只靠 emptyDir 上的文件；若继续执行要在 agent 侧挂起恢复，接线范围要写进本轮。实现和测试不改清单，官方探测和 Secret 留给部署。后对实现补充的三点再写「通过」。确认前不写 tdd、不改代码。
- 部署：通过。本轮实现和测试不动 `deploy/k8s`；若要把签名密钥挂进 Secret，或调整 agentpass 的 Service、环境变量，在 ATDD 全部通过后再开 PR，不直接推 master。生产密钥不进 Git、日志和群消息。生产缺少 `JASAGENT_AGENTPASS_SIGNING_KEY` 必须失败，不得再静默生成。agentpass 不暴露到公网，审批界面只经网关访问 `jasagent-web`；官方探测须保持 `PROBE PASSED`，018、021、022 已锁定的出站和异步验收不能缩小，`/v1/tickets` 的领证、校验、核销约定也不改。后对并入的补充和接线范围再写「通过」。确认前不部署。
- 代码review：通过。实现补充的三点、测试锁定的可测项，以及领队补的接线范围，并入后没有新的反对项。确认前不写文件、不改代码、不部署。
- Hang：于 2026-11-20 约 08:21（Asia/Shanghai）确认「通行证需要持久化权限判断 开始做吧」，作为对上述已锁定口径的确认。

## 确认后的口径

1. 本轮编号为 **023-agentpass-tenant-durable**。实现改 `jasagent-agentpass`、`jasagent-agentpass-server`、`jasagent-agentpass-starter`，以及接线所需的 `jasagent-api` / `jasagent-auth` / `jasagent-persist`。要把发证前的会话和权限改走 020 的 MySQL，以及 PENDING 挂起、控制台审批后用原 executionId 继续，已经在这条链上的模块也可以改，尤其是 `jasagent-app`、`jasagent-controller` 和 `jasagent-web`。审批界面只放 `jasagent-web`，并经网关访问。不改 sidecar。实现和测试阶段不改 `deploy/k8s`。
2. 发证前的会话、租户和权限以 020 的 MySQL 为准，不以 emptyDir 上的会话文件或 `policies.yaml` 为真相来源。策略和待批不能再只靠 emptyDir 上的文件。
3. 证状态和流水落到已有 MySQL。用同一库和同一签名密钥环境变量重启 agentpass 进程后，已核销的证仍须拒绝重放；未过期、未使用的证在签名密钥未变时仍可核销。
4. 租户隔离必须成立，验收要用不同租户并发覆盖：A 不能领、验、核销 B 的证；流水按租户隔离，不得对外倾倒或串到别的租户。
5. `GET /v1/tickets/audit` 必须鉴权并按租户过滤。未鉴权应 401 或 403，不得再回全部流水；已登录也只能看本租户。
6. 未命中策略须留下待批，不能只测领证 403。同一用户经 `jasagent-web`、走网关批准或拒绝；批准后走运行时已有的 resume 路径，用原来的 `executionId` 继续完成该次调用；他人不能代批。
7. 生产缺少 `JASAGENT_AGENTPASS_SIGNING_KEY` 必须失败，不得再静默在内存里另生成一把。本地和验收可另给测试密钥。密钥只在运行时注入，不进 Git、说明、日志、异常或响应。集群内 Service 必须能访问到 agentpass 服务。不把 agentpass 暴露到公网。
8. `/v1/tickets` 的领证、校验、核销约定不变。018 出站、021 sidecar 线程和 022 运行时异步验收保持不变。官方探测仍须 `PROBE PASSED`。
9. 从 020 起的流程继续有效：实现按本 spec 开 Pull Request，由 JaSAgent 代码review 对照本规格审查后再交给测试。测试全部通过后，部署如需调整 `deploy/k8s` 或 `deploy.md` 再开 PR，审查后再合并、再上集群。调研把本目录的 `review.md` 和 `spec.md` 先交到仓库，不挡开工。不直接推产品代码到 master。
10. 本轮改到的模块必须补齐注释、常量、类、方法、测试代码和可执行测试脚本。口令、令牌、票据和密钥不进 Git、日志、异常或响应。变更记录不能代替本轮 ATDD。验收以部署的云电脑为准。
11. 界面仍只放在 `jasagent-web`。网关仍按单副本。公网入口维持 019。每会话关注点和技能自动摘要、日程推送、多媒体、外部插件、CodeReview、网关高可用、整块 K8s，以及 019 尚未正式关闭的项，都不在本轮。
