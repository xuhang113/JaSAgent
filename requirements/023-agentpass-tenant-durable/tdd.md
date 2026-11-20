# 测试规范 023

**来源**: 同目录 `spec.md` 的测试要求

第 1 到第 9 条在 ATDD 阶段验收。第 10 到第 12 条（官方探测、生产签名密钥 Secret、`deploy.md`）在部署阶段由部署验收，本文件不列入。

本目录用例不调用 `scripts/test-023-agentpass-tenant-durable.sh`：该脚本把对比范围写死为实现模块、本轮脚本和变更记录，ATDD 文件会被判成超范围；另外它用 `git diff` 全文检索 `/v1/approvals` 和 `/api/agentpass`，会把自己脚本里的这条检查误判成第二套审批接口。进程级验收由 `tests/live.sh` 独立拉起 `jasagent-agentpass-server` 和 `jasagent-app` 完成。

## 用例

| 用例 | 来源 | 做什么 | 则 | 脚本 | 结果文件 | 日志文件 |
| --- | --- | --- | --- | --- | --- | --- |
| T-001 | 测试要求 1 | 查看发证前的会话、租户、权限和策略来源 | 发证前的会话、租户和权限以 020 的 MySQL 为准，不以 emptyDir 上的会话文件或 `policies.yaml` 为真相来源。策略和待批不能再只靠 emptyDir 上的文件。 | `tests/T-001` | `results/T-001.txt` | `logs/T-001.log` |
| T-002 | 测试要求 2 | 查看证状态落库、重启后拒绝重放 | 证状态和流水已落到已有 MySQL。用同一库和同一签名密钥环境变量重启 agentpass 进程后，已核销的证仍须拒绝重放；未过期、未使用的证在签名密钥未变时仍可核销。 | `tests/T-002` | `results/T-002.txt` | `logs/T-002.log` |
| T-003 | 测试要求 3 | 查看不同租户并发隔离 | 不同租户须并发覆盖：A 不能领、验、核销 B 的证；流水按租户隔离，不得串到别的租户。 | `tests/T-003` | `results/T-003.txt` | `logs/T-003.log` |
| T-004 | 测试要求 4 | 查看 audit 鉴权和租户过滤 | `GET /v1/tickets/audit` 未鉴权应 401 或 403，不得再回全部流水；已登录也只能看本租户。 | `tests/T-004` | `results/T-004.txt` | `logs/T-004.log` |
| T-005 | 测试要求 5 | 查看领证约定和 018、021、022 | `/v1/tickets` 的领证、校验、核销约定不变。018 出站、021 sidecar 线程和 022 运行时异步验收仍然成立。 | `tests/T-005` | `results/T-005.txt` | `logs/T-005.log` |
| T-006 | 测试要求 6 | 查看待批、已有审批路径和原 executionId 恢复 | 未命中策略须留下待批，不能只测领证 403。同一用户经 `jasagent-web`、走网关批准或拒绝；批准后走已有 resume 路径，用原来的 `executionId` 继续完成该次调用；他人代批须被拒绝。验收接上已有的 `POST /api/executions/{id}/passes/{passId}`，不另做一套审批接口。 | `tests/T-006` | `results/T-006.txt` | `logs/T-006.log` |
| T-007 | 测试要求 7 | 查看缺签名密钥失败和密文约束 | 生产缺少 `JASAGENT_AGENTPASS_SIGNING_KEY` 必须失败，不得再静默在内存里另生成一把。本地和验收可另给测试密钥。密钥只在运行时注入，不出现在仓库、说明或日志里。 | `tests/T-007` | `results/T-007.txt` | `logs/T-007.log` |
| T-008 | 测试要求 8 | 查看实现改动覆盖的模块和回环监听 | 实现改动只覆盖 agentpass 及相关接线模块（`jasagent-agentpass`、`jasagent-agentpass-server`、`jasagent-agentpass-starter`，以及接线所需的 `jasagent-api` / `jasagent-auth` / `jasagent-persist`；要把发证前的会话和权限改走 020 的 MySQL，以及 PENDING 挂起、控制台审批后用原 executionId 继续，已经在这条链上的模块也可以改，尤其是 `jasagent-app`、`jasagent-controller` 和 `jasagent-web`；审批或待批查询的现有网关路由接不上时，也可改 `jasagent-gateway` 的接线）。不改 sidecar。不把 agentpass 暴露到公网。 | `tests/T-008` | `results/T-008.txt` | `logs/T-008.log` |
| T-009 | 测试要求 9 | 检查清单、注释、自动测试和可执行脚本 | 实现和测试提交不修改 `deploy/k8s` 下的清单文件。本轮改到的模块有注释，有自动测试，并且有可执行的测试脚本。 | `tests/T-009` | `results/T-009.txt` | `logs/T-009.log` |

## 不能生成

没有不能生成。

## 结果

`results/<用例编号>.txt` 只在脚本真实执行后出现。一行，制表符分开：

用例编号	通过或失败	则	耗时毫秒	日志文件

## 执行日志

`logs/<用例编号>.log` 只在脚本真实执行后出现。一行一个检查点，制表符分开：

时间	用例编号	检查点	耗时毫秒	可观察结果
