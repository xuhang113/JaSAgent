# 004 workflow 与 ReAct

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：用 langgraph4j 1.9.9 执行工作流 DSL 和 ReAct。图由 `CompiledGraph` 推进，检查点默认用 `SQLiteSaverV2`，JDBC URL 以 `jdbc:postgresql:` 开头时用 `PostgresSaverV2`。图 JSON 来自 `JsonDslGenerator`，节点和边的 trace 来自 OpenTelemetry hook。消息顺序、`maxSteps`、插件阶段、grounding 只重试一次、条件分支、子流程和 interrupt 恢复按规格实现。不使用 Spring，也不使用 Spring AI。
- 涉及模块：`jasagent-workflow`，以及 `jasagent-api` 的 `ErrorCode` 增加 `WORKFLOW`、`WORKFLOW_MAX_STEPS`、`PLUGIN`、`LLM`、`TOOL`。父工程模块列表加入 `jasagent-workflow`。
- 怎么验证：仓库根 `mvn test` 通过，其中 `LoginDirectoryTest` 18 个、`WorkflowReactTest` 29 个。`requirements/004-workflow-react/tests/004-workflow-react.sh` 结果为 PASS。验收脚本在实现前曾因反应器里没有该模块而失败。
