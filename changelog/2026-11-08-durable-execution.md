# 2026-11-08 执行落到 JDBC 和 langgraph

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：把会话控制台和外部插件工具合进实现分支。提交、中止、恢复改走 WorkflowEngine、JDBC 和 CheckpointRunControl。执行状态与 traceId 以 JDBC 为准，检查点留在 langgraph saver。没配模型 key 时执行是 FAILED，errorCode 为 OFFLINE。SSE 跟到终态。健康检查返回 storage up 或 down。上下文压缩默认 0.85，压缩失败不替换已发布摘要。启动脚本要求 Java 21 或更高。
- 涉及模块：jasagent-app、jasagent-controller、jasagent-persist、jasagent-core、scripts
- 怎么验证：Java 21 下仓库根 `mvn test`，BUILD SUCCESS。RestartResumeTest 在关掉第一个运行时后，用原 executionId resume，子 span 的 traceId 与 JDBC 里保存的一致。`/health` 在存储可连时是 `status=ok`、`storage=up`；PostgreSQL 连不上时 `storageReachable` 为 false。默认 java 是 17 时，启动脚本改用本机 Java 21 并写明原因，不会选中 17。
