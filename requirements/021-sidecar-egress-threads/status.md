# 021-sidecar-egress-threads 状态

## 2026-11-19（Asia/Shanghai）

- 日期：2026-11-19（Asia/Shanghai）
- 开评结论：今天不开评。

### 上一件完成情况

上一件 `021-sidecar-egress-threads`（sidecar 出站不再占用 Tomcat 平台工作线程）已整块完成。

- `review.md`、`spec.md` 已在 [`7f1b96b`](https://github.com/xuhang113/JaSAgent/commit/7f1b96bf8d691a0e86c84814dea32d2c6139bff0) 落地（2026-11-18 18:21 Asia/Shanghai），记录 Hang 于约 18:18 确认「开工021」。
- 实现已在 [`d844314`](https://github.com/xuhang113/JaSAgent/commit/d84431405c1fb25758d0f9d6bc7ce5085a181588) 落地，并有 `changelog/2026-11-18-sidecar-egress-threads.md`。ATDD 结果补在 [`901361c`](https://github.com/xuhang113/JaSAgent/commit/901361c53691ac32bc78551730178c7135e27914)。两者随 [PR #9](https://github.com/xuhang113/JaSAgent/pull/9) 合入，合并提交为 [`1762100`](https://github.com/xuhang113/JaSAgent/commit/1762100c6dff286dd001af6b17c08fe93bbfb0fe)（20:21:09 Asia/Shanghai）。
- ATDD：`tdd.md`、`tests/`、`logs/`、`results/T-001.txt` 至 `results/T-009.txt`。T-001 到 T-009 全部为「通过」，对应规格第 1 到第 9 条。
- 部署已在 [PR #10](https://github.com/xuhang113/JaSAgent/pull/10) 落地并合入，合并后 master 为 [`11f497c`](https://github.com/xuhang113/JaSAgent/commit/11f497c887a9eb16620f274bcc48f15a01f0099b)（20:21:35 Asia/Shanghai）：`deploy.md` 记录云电脑实测。官方 `probe-egress` 于 20:17:09 为 `PROBE PASSED`；I-001 至 I-016 于 20:17:25 全部通过；公网 `/health` 于 20:18:20 返回 200。规格第 10 到第 12 条均已通过。生产 `deploy/k8s` 清单未永久压低线程上限。两份 PR 都由 xuhang113 按审查意见先合 #9、再合 #10，没有直接推 master。

本次开评核对时（2026-11-19 约 07:14 Asia/Shanghai），master 仍为 [`11f497c887a9eb16620f274bcc48f15a01f0099b`](https://github.com/xuhang113/JaSAgent/commit/11f497c887a9eb16620f274bcc48f15a01f0099b)。之后没有新的 master 提交，没有 022 / runtime 分支或 Pull Request，也没有 `requirements/022` 目录。唯一未关闭的 PR 仍是无关的 [#3](https://github.com/xuhang113/JaSAgent/pull/3)。

### 今天没有开评的原因

021 已整块完成且测试通过。按顺序下一棒是运行时。候选 `022-runtime-full-async` 已于 2026-11-18 20:53 由调研在群里交出；领队、调研、实现、测试、部署、代码review 均已写「通过」，补充已并进已锁定口径。领队已于 2026-11-18 22:18（Asia/Shanghai）在群里请 Hang 确认是否开工。

截至本次开评核对，领队 1:1 和群里在 22:18 请求之后均未见 Hang 确认；仓库里也还没有 022 的 `spec.md` 或代码。一轮只推进一块，因此今天不开新评，也不再让调研另交候选。下一棒仍是等 Hang 确认开工 022，而不是开 023。

确认前不写 `spec.md`，不改 Git 上或 Hang 的 Mac 上的代码，不部署。

已锁定口径摘要：本轮只改运行时相关模块（`jasagent-llm`、`jasagent-app`，以及接线所需的 `jasagent-workflow` / `jasagent-controller`；若调度器等运行时模块在等待时仍占用 agent 的操作系统平台线程，也留在本轮），不改 sidecar、网关和 `jasagent-web`。等待 sidecar、模型流或工具，以及恢复执行期间，不得占用 agent（`jasagent-app` 进程）的操作系统平台工作线程。优先用现有 `VirtualIo`、虚拟线程或 Servlet 异步，不整模块改成 WebFlux。读流时不得钉死载体；agent 侧出站也要避开 021 里 JDK `ofInputStream().read` 在 `synchronized` 里钉住载体那种读法。`/v1/forward` 约定、018 出站和 021 sidecar 验收保持不变，官方探测仍须为 `PROBE PASSED`。验收须故意压低 agent 的 Tomcat 平台工作线程（不能只调 `server.tomcat.threads.max`），再并发跑超过该上限的长 ReAct / 流式调用（含恢复路径）；思考或首块须在上游结束前到达，期间 `/health` 和新的提交、发消息须在超时内可用。压低只用于验收，不永久改低生产 `deploy/k8s` 清单。异步移交时，会话、租户和权限上下文不得丢失，也不得串到别人的请求上；020 的租户隔离仍须成立，验收要用不同租户并发覆盖这一点。流程仍是：实现开 PR → 代码审查 → 测试 → 部署开 PR，不直接推 master。

### 今天有没有开评

没有开评。原因见上：021 已完成，022 已全员通过，仍待 Hang 确认开工。
