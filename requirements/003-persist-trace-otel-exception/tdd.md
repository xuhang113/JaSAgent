# 003 持久化、追踪、OpenTelemetry 与异常的测试用例

## 1. defaultH2FileUnderWorkspaceData

要求：未设置 `JASAGENT_JDBC_URL` 时，执行库是 H2 文件，路径为工作区下的 `jasagent/data/jasagent`。

看到的结果：打开后的 URL 指向该工作区绝对路径下的 `jasagent/data/jasagent`，并且同目录出现 `jasagent.mv.db`。

## 2. jdbcUrlOverridesDefault

要求：设置 `JASAGENT_JDBC_URL` 后，执行库使用该 URL，并且不会另建默认 H2 文件。

看到的结果：连接 URL 等于传入的 H2 URL，自定义文件存在，默认的 `jasagent/data/jasagent.mv.db` 不存在。

## 3. executionColumnsExist

要求：Flyway 迁移之后，`execution` 表含有 `execution_id`、`user_id`、`session_id`、`workspace_path`、`status`、`otel_trace_id`、`otel_root_span_id`。

看到的结果：`information_schema` 里这七个列都能按不区分大小写的名字找到。

## 4. processTablesExist

要求：Flyway 迁移之后，存在 `agent`、`task`、`message`、`correction_note`、`context_summary`、`llm_call`、`tool_call`、`plugin_call`、`retrieval`、`evaluation`。

看到的结果：这十张表都能在 `information_schema` 里找到。

## 5. executionRoundTripKeepsOtelIds

要求：写入的执行记录能按 executionId 读回，其中包含原来的 traceId 和根 span id。

看到的结果：读回的用户、会话、工作区、状态、traceId 和根 span id 与写入的那一行相同。

## 6. llmCallStoresProviderModelLatencyTokens

要求：模型调用写入供应方、模型名、延迟和 token 用量，并能按调用 id 读回。

看到的结果：读回的供应方、模型名、延迟、prompt token、completion token 和 total token 与写入的相同。

## 7. taskFailedStatusIsStored

要求：任务可以标为 `FAILED`，并记下错误码。

看到的结果：标记之后任务状态是 `FAILED`，错误码等于传入的 `TOOL`。

## 8. passwordIsNotWrittenIntoWorkspace

要求：`JASAGENT_JDBC_PASSWORD` 只留在连接设置中，不写入工作区里除数据库文件以外的文件。

看到的结果：连接设置中的密码等于环境变量里的占位值，工作区里数据库文件以外的文本文件都不包含该占位值。

## 9. nodeCheckpointUsesSqliteSaver

要求：默认节点检查点的 saver 是 `SQLiteSaverV2`，文件为工作区下的 `jasagent/data/checkpoints.db`，提交后该 SQLite 库中有 `LG4JCheckpoint` 行。

看到的结果：saver 的运行时类型是 `SQLiteSaverV2`，SQLite 文件位于该路径，`LG4JCheckpoint` 的行数大于零。

## 10. graphCompileUsesCheckpointSaver

要求：把该 saver 交给 langgraph4j 的 `CompileConfig` 后，图执行能在 saver 中留下对应线程的检查点。

看到的结果：图调用成功返回，`saver.list` 对同一 threadId 的结果不是空集合。

## 11. checkpointSurvivesReopen

要求：关闭并重新打开同一个检查点文件后，仍能按 executionId 读回已提交的节点和状态。

看到的结果：第二次打开读到的节点是 `find`，下一节点是 `act`，状态里的 `step` 仍是 `find`。

## 12. failedCheckpointDoesNotEnterNextNode

要求：检查点提交失败时，完成异常的错误码是 `PERSIST`，下一节点的动作不会执行。

看到的结果：提交抛出 `PERSIST`，下一节点的标志仍为假。

## 13. postgresUrlSelectsPostgresSaver

要求：JDBC URL 以 `jdbc:postgresql:` 开头时，打开检查点会进入 `PostgresSaverV2`，并且不创建默认的 SQLite 检查点文件。

看到的结果：打开失败的异常链里出现 `PostgresSaverV2`，错误码是 `PERSIST`，`checkpoints.db` 不存在。

## 14. executionDatabaseHasNoCustomCheckpointTable

要求：执行过程使用的 H2 库中不存在 `node_checkpoint`、`LG4JCheckpoint`、`LG4JThread` 或 `LG4JThreadTag`。

看到的结果：这四张表都不在执行库的 `information_schema` 中。

## 15. traceFilesUnderExecutionDirectory

要求：一次执行的 `trace.log` 和 `events.jsonl` 位于工作区下的 `jasagent/logs/{executionId}`。

看到的结果：两个文件都在该目录下，并且目录路径等于工作区追加 `jasagent/logs/{executionId}`。

## 16. eventsCoverRequiredKinds

要求：受理、登录用户、目录、调度、合并、节点、模型路由、检索和溯源、通行证决定、工具、插件、压缩、纠正 note、中止、恢复、评估、终态都会写入 `trace.log` 和 `events.jsonl`。

看到的结果：每一种事件名都同时出现在 `trace.log` 和 `events.jsonl` 中。

## 17. authorizationValueIsRemoved

要求：事件正文里的 `Authorization` 值不会出现在 `trace.log` 或 `events.jsonl` 中。

看到的结果：两个文件的全文都不包含本次写入的令牌，也都不包含 `Bearer`。

## 18. stackGoesToTraceLog

要求：异常堆栈写入该次执行的 `trace.log`。

看到的结果：`trace.log` 含有异常信息和堆栈帧。

## 19. rootSpanNameAndAttribute

要求：根 span 的名字是 `jasagent.execution`，并且带有 `jasagent.execution_id`。

看到的结果：已结束的根 span 名字等于 `jasagent.execution`，属性 `jasagent.execution_id` 等于本次 executionId。

## 20. childSpansShareSavedTraceId

要求：同一次执行的根 span 和全部子 span 共用一个 traceId，该 traceId 与根 span id 能从这次执行的已保存标识读出。

看到的结果：九个子 span 与根 span 的 traceId 都等于已保存的 traceId，每个子 span 的父 span id 等于已保存的根 span id。

## 21. asyncHandoffKeepsTraceId

要求：异步交接之后，虚拟线程上的当前 span 仍属于已保存的 traceId，交接返回后调用方线程上没有留下该 span。

看到的结果：交接内部读到的 traceId 等于已保存的 traceId，返回后调用方的当前 span 上下文无效。

## 22. defaultDoesNotExportOtlp

要求：未配置 `jasagent.otel.endpoint` 时不启用 OTLP 导出，导出器类名为空。

看到的结果：导出标志为假，导出器类名是空字符串。

## 23. endpointEnablesOtlpExporter

要求：配置 `jasagent.otel.endpoint` 后，导出器是 `OtlpHttpSpanExporter`，并且导出已启用。

看到的结果：导出标志为真，导出器类名是 `io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter`。

## 24. errorCodesPresent

要求：`ErrorCode` 含有 `LLM`、`TOOL`、`PLUGIN`、`MCP`、`RAG`、`WORKFLOW`、`WORKFLOW_MAX_STEPS`、`PERSIST`、`AUTH`、`INTERRUPTED`、`CANCELLED`、`INTERNAL`。

看到的结果：这些枚举常量全部存在。

## 25. swallowedNullIsRejected

要求：异步调用自己吞掉异常并返回空时，边界以错误码 `INTERNAL` 失败，并带上 executionId。

看到的结果：完成异常的错误码是 `INTERNAL`，executionId 等于传入值。

## 26. thrownExceptionIsNotReturnedAsNull

要求：异步调用直接抛出异常时，边界以异常完成，结果不是空值，并且异常带有 executionId。

看到的结果：`join` 抛出带有 executionId 和原因的 `AgentException`，没有返回空。

## 27. consumerMarksTaskFailedAndContinues

要求：消费者遇到未捕获异常时把该任务标为失败，本次结果为失败，下一次消费仍能成功完成。

看到的结果：第一次结果是 `FAILED`，失败回调收到该任务和错误码 `TOOL`，第二次结果是 `COMPLETED`。

## 28. httpBodyHasExecutionIdAndCodeWithoutStack

要求：HTTP 错误体含有 executionId 和错误码，堆栈文本只进入 trace 回调，不进入错误体。

看到的结果：错误体的 executionId 和错误码与异常一致，错误体文本不含堆栈标记，trace 回调收到的文本含有该标记和堆栈帧。

## 29. guardRunsOnVirtualThread

要求：异步边界在虚拟线程上执行调用。

看到的结果：调用内部观察到的线程是虚拟线程。
