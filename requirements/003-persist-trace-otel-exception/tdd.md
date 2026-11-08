# 003 持久化、可读日志、追踪和异常的测试用例

## 1. executionRoundTrip

要求：执行记录能写入并读回 `execution_id`、`user_id`、`session_id`、`workspace_path`、`status`、`otel_trace_id`、`otel_root_span_id`。

看到的结果：读回的这七个字段与写入值一致。

## 2. defaultJdbcPathIsWorkspaceH2

要求：默认 JDBC 地址指向工作区下的 `jasagent/data/jasagent` H2 文件。

看到的结果：地址以 `jdbc:h2:file:` 开头，并且包含该工作区下的 `jasagent/data/jasagent`。

## 3. jdbcUrlComesFromEnvNotProperties

要求：设置 `JASAGENT_JDBC_URL` 时使用该地址，配置映射里的口令不会被采用。

看到的结果：地址等于环境映射中的 URL，口令为空，而不是配置映射里的占位符。

## 4. reopenKeepsExecution

要求：关闭存储后重新打开同一文件，执行记录仍然能读到。

看到的结果：新存储读到的 `execution_id` 和 `otel_trace_id` 与关闭前一致。

## 5. rootSpanNameAndExecutionAttribute

要求：根 span 的名字是 `jasagent.execution`，并且属性 `jasagent.execution_id` 等于这次执行编号。

看到的结果：名字等于 `jasagent.execution`，属性值等于传入的执行编号。

## 6. childSpanSharesTraceId

要求：同一次执行的子 span 共用根 span 的 traceId。

看到的结果：子 span 的 traceId 等于根 span 的 traceId，属性 `jasagent.execution_id` 仍是该执行编号。

## 7. restartedTracerKeepsSavedTraceId

要求：用新的追踪器带上保存的 traceId 创建子 span 时，traceId 保持不变。

看到的结果：新追踪器创建的子 span traceId 等于原来保存的 traceId。

## 8. blankEndpointDoesNotExport

要求：没有配置导出地址时不启用 OTLP 导出。

看到的结果：`exporting` 为假。

## 9. traceFilesExist

要求：写入事件后，执行目录里同时存在 `trace.log` 和 `events.jsonl`。

看到的结果：两个文件都存在且非空。

## 10. acceptedAndTerminalEvents

要求：日志里能看到受理事件和终态事件。

看到的结果：`events.jsonl` 同时包含 `accepted` 和 `terminal`。

## 11. traceRedactsAuthorization

要求：日志中的 `Authorization` 密钥会被去掉。

看到的结果：`trace.log` 不包含原来的密钥占位符。

## 12. nullBoundaryFails

要求：异步边界收到 null 时以错误码 `INTERNAL` 失败，而不是成功返回 null。

看到的结果：等待结果抛出 `INTERNAL`，并且该阶段以异常结束。

## 13. consumerMarksFailedAndContinues

要求：消费者里未捕获的异常把该任务标为 `FAILED`，随后的任务仍然会执行。

看到的结果：第一条任务的状态是 `FAILED`，第二条任务的动作已经执行。

## 14. errorCodesCoverSpec

要求：错误码包含 `LLM`、`TOOL`、`PLUGIN`、`MCP`、`RAG`、`WORKFLOW`、`PERSIST`、`AUTH`、`INTERRUPTED`、`CANCELLED`、`INTERNAL` 和 `WORKFLOW_MAX_STEPS`。

看到的结果：这些名字都能通过 `ErrorCode.valueOf` 找到。
