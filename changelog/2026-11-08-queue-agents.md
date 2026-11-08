# 006 主从 agent 队列

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：主 agent 只调度。每次最多取出 `jasagent.scheduler.batch-size` 条，缺省 4。`sessionId` 相同相关度 1.0，`correlationKey` 相同 0.9，否则用目标文本的 token Jaccard。达到 `merge-threshold`（缺省 0.6）就按单链接合并。合并后的工作项优先追加给相关且未完成的子 agent，该子 agent 未完成条数达到 `queue-cap`（缺省 8）就新建。每个 agent 一条队列，队列内串行，子 agent 之间并行。子 agent 通过 api 的 `SubAgentRunner` 执行，主 agent 不调用它。
- 涉及模块：`jasagent-core`，以及 `jasagent-api` 新增 `SubAgentRunner`。
- 怎么验证：仓库根 `mvn test` 通过，其中 `QueueAgentTest` 36 个，此前各模块测试一起通过，失败 0，错误 0，跳过 0。`requirements/006-queue-agents/tests/006-queue-agents.sh` 在实现前因找不到调度类而失败，实现后结果为 PASS。
