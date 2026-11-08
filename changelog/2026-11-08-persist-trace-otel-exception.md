# 2026-11-08 持久化、可读日志、追踪和异常

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：用 Flyway 建立执行过程表，默认 H2 文件可按环境变量换成外部 JDBC；按执行编号写 trace.log 和 events.jsonl；同一次执行共用 traceId；异步边界不再吞掉异常。
- 涉及模块：jasagent-api、jasagent-persist、jasagent-trace、jasagent-otel、jasagent-exception
- 怎么验证：在仓库根目录执行 `mvn test`。此前用例与本次 `PersistStoreTest` 4 项、`FileTraceTest` 3 项、`OtelTracerTest` 4 项、`AsyncBoundaryTest` 3 项全部通过，失败 0，错误 0，跳过 0。验收脚本 `requirements/003-persist-trace-otel-exception/tests/003-persist-trace-otel-exception.sh` 退出码为 0。
