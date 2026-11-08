# 2026-11-08 持久化、追踪、OpenTelemetry 与异常

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：执行过程用 JDBC 与 Flyway 落在默认 H2 文件，可用 `JASAGENT_JDBC_URL` 换成 PostgreSQL。节点检查点使用 langgraph4j 的 `SQLiteSaverV2`，PostgreSQL 时使用 `PostgresSaverV2`。同一次执行写入 `trace.log` 与 `events.jsonl`，根 span 与子 span 共用 traceId，未配置 `jasagent.otel.endpoint` 时不导出 OTLP。异步边界不再把异常收成空结果，消费者把失败任务标为 `FAILED` 后继续接受下一次调用。JDBC 密码只从 `JASAGENT_JDBC_PASSWORD` 读取。
- 涉及模块：jasagent-api、jasagent-persist、jasagent-trace、jasagent-otel、jasagent-exception
- 怎么验证：在仓库根目录执行 `mvn test`。`LoginDirectoryTest` 18 项、`PersistProcessTest` 14 项、`ExecutionTraceTest` 4 项、`ExecutionOtelTest` 5 项、`AsyncExceptionTest` 6 项通过，失败 0，错误 0，跳过 0。验收脚本 `requirements/003-persist-trace-otel-exception/tests/003-persist-trace-otel-exception.sh` 退出码为 0。
