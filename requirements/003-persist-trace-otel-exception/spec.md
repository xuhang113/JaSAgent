# 003 持久化、可读日志、追踪和异常

## 这次要改什么

现状是登录和模型路由已经可用，执行过程还不能落库，也没有按执行编号分开的可读日志、统一 traceId 和异步异常边界。这次补上 JDBC 存储、文件日志、OpenTelemetry 追踪和异常边界。不改工作流步骤机、队列调度、检索和页面。

## 模块

这次扩展 `jasagent-api` 的错误码和端口，并新增 `jasagent-persist`、`jasagent-trace`、`jasagent-otel`、`jasagent-exception`。这四个模块的 Maven 依赖只指向 `jasagent-api`，以及各自的 JDBC、Flyway 或 OpenTelemetry 库。它们互不依赖。

## 改动地方

默认数据库是工作区目录下 `jasagent/data/jasagent` 的 H2 文件。`JASAGENT_JDBC_URL` 可以换成 PostgreSQL。用户名读取 `JASAGENT_JDBC_USER`，缺省为 `sa`。口令只读取 `JASAGENT_JDBC_PASSWORD`，缺省为空字符串。配置映射里的口令忽略。仓库和示例里不写真实口令。

Flyway 管理表。`execution` 至少含 `execution_id`、`user_id`、`session_id`、`workspace_path`、`status`、`otel_trace_id`、`otel_root_span_id`。同时建 `agent`、`task`、`message`、`correction_note`、`context_summary`、`node_checkpoint`、`llm_call`、`tool_call`、`plugin_call`、`retrieval`、`evaluation`。关闭存储后再打开同一文件，原来的执行记录仍能读到。

可读日志目录是 `<workspace>/jasagent/logs/{executionId}/`，里面有 `trace.log` 和 `events.jsonl`。写入前去掉 `Authorization`。这次至少记录受理和终态两类事件。

根 span 名是 `jasagent.execution`。子 span 带属性 `jasagent.execution_id`。同一次执行的子 span 共用保存下来的 traceId，包括用新的追踪器按保存的 traceId 接着建子 span。`jasagent.otel.endpoint` 为空时不安装 OTLP 导出器。配置了端点才走 OTLP。默认测试不向外部发送。

错误码补齐 `TOOL`、`PLUGIN`、`MCP`、`RAG`、`WORKFLOW`、`PERSIST`、`INTERRUPTED`、`CANCELLED`、`WORKFLOW_MAX_STEPS`，并保留已有的 `LLM`、`AUTH`、`INTERNAL`。异步边界收到 null 时以 `INTERNAL` 失败，不把 null 当成成功。消费者里未捕获的异常把该任务标为 `FAILED`，进程继续处理下一条。

阻塞的 JDBC 和文件读写放进虚拟线程执行器，对外返回 `CompletionStage`。

## 数据库

这次新增上一节列出的表。迁移脚本只追加，不在后续需求里改已发布的脚本。

## 中间件

JDBC 使用 H2 文件或调用方给出的 JDBC 地址。OpenTelemetry 只在配置了端点时导出 OTLP。这次不启动业务 HTTP，也不连接消息中间件。

## 测试要求

1. 执行记录能写入并读回 `execution_id`、`user_id`、`session_id`、`workspace_path`、`status`、`otel_trace_id`、`otel_root_span_id`。
2. 默认 JDBC 地址指向工作区下的 `jasagent/data/jasagent` H2 文件。
3. 设置 `JASAGENT_JDBC_URL` 时使用该地址，配置映射里的口令不会被采用。
4. 关闭存储后重新打开同一文件，执行记录仍然能读到。
5. 根 span 的名字是 `jasagent.execution`，并且属性 `jasagent.execution_id` 等于这次执行编号。
6. 同一次执行的子 span 共用根 span 的 traceId。
7. 用新的追踪器带上保存的 traceId 创建子 span 时，traceId 保持不变。
8. 没有配置导出地址时不启用 OTLP 导出。
9. 写入事件后，执行目录里同时存在 `trace.log` 和 `events.jsonl`。
10. 日志里能看到受理事件和终态事件。
11. 日志中的 `Authorization` 密钥会被去掉。
12. 异步边界收到 null 时以错误码 `INTERNAL` 失败，而不是成功返回 null。
13. 消费者里未捕获的异常把该任务标为 `FAILED`，随后的任务仍然会执行。
14. 错误码包含 `LLM`、`TOOL`、`PLUGIN`、`MCP`、`RAG`、`WORKFLOW`、`PERSIST`、`AUTH`、`INTERRUPTED`、`CANCELLED`、`INTERNAL` 和 `WORKFLOW_MAX_STEPS`。

## 还没定

无。H2 的默认用户名 `sa` 和空口令按本节实现。
