# 2026-11-08 测试基线

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：补上端口、健康检查、SSE 终态、执行中止与恢复、会话插件工具、上下文压缩和 012 登录流程的测试。没有改产品行为。产品还做不到的断言保持失败。
- 涉及模块：jasagent-app
- 怎么验证：Java 21 下仓库根 `mvn test -B`。新增 21 条。失败 4 条：`httpInterruptStopsRunningExecution`、`contextBelowThresholdDoesNotCompress`、`contextAtThresholdKeepsPublishedSummary`、`resumeAfterRuntimeCloseReachesNewTerminal`。其余通过。
