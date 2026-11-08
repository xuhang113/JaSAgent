# 007 上下文与纠正 note

## 这次要改什么

现状是 `jasagent-core` 只有主从队列。`jasagent-api` 里已经有 `CorrectionNote`、`CorrectionSource`、`ChatModel`、`ChatRequest` 和 `LlmTask.COMPRESS`。`CorrectionSource` 的取值是 `USER`、`TOOL_CONTRADICTION`、`GROUNDING`、`EVALUATION`。工作流模块会按固定顺序组消息，但超过预算时不会压缩，纠正 note 也不会写入用户目录。队列调度已经按 006 工作。

这次在 `jasagent-core` 增加上下文窗口、纠正 note 存放，以及工具结果和助手记忆冲突时的处理。压缩通过 `ChatModel.complete` 发出任务 `compress`。不改 `QueueAgentScheduler`、`WorkCorrelation`、`SchedulerSettings` 和 `SubAgentRunner` 的行为。不改 `jasagent-web`、`jasagent-workflow`、`jasagent-llm`、`jasagent-persist`、`jasagent-controller`、`jasagent-cli` 和 `jasagent-api`。`POST /api/sessions/{sessionId}/corrections` 留给 012，这次只提供它要调用的追加方法。

## 模块

只改 `jasagent-core`。它仍然只依赖 `jasagent-api`。测试依赖 JUnit。不增加 Spring、Spring AI、langgraph4j 或 LangChain4j。

## 改动地方

纠正 note 用 api 里的 `CorrectionNote`。范围是 `sessionId`。字段是 `noteId`、`sessionId`、`executionId`、`createdAt`、`source`、`text`。`source` 只用 `CorrectionSource` 的四个值。

`CorrectionStore.append` 返回 `CompletionStage<Void>`。文件写在调用方传入的用户目录下，路径是 `corrections/<sessionId>/<noteId>.json`。JSON 按这个字段顺序写出全部字段，`createdAt` 用 `Instant` 的 ISO-8601 文本，`source` 用枚举名。正文里的引号和反斜杠按 JSON 转义。文件写完后，把同一条 note 交给快照消费者。`noteId` 或 `sessionId` 含有 `/`、`\`、`..`，或者字段为 null 或空白时，追加失败，错误码是 `INTERNAL`，不写文件，也不调用快照消费者。

`ContextWindow.DEFAULT_BUDGET_TOKENS` 是 8000。调用方把这次的 `budgetTokens` 传进来。小于 1 的预算，或空白的 `executionId`，`prepare` 失败，错误码是 `INTERNAL`，不调用模型。`prepare` 返回 `CompletionStage<ContextPrepared>`。

组好的消息顺序是：一条 system，然后是压缩摘要，然后是保留下来的轮次，然后是全部纠正 note，然后是当前用户消息。system 含有角色、规则、技能、未完成任务板和 `executionId`。技能在正文还在时，一行里依次是名称、描述、正文。摘要的角色是 `summary`，没有摘要时不放这条消息。纠正 note 的角色是 `correction`，正文是 note 的 `text`，顺序与传入顺序一致，整段紧挨在当前用户消息之前。当前用户消息的角色是 `user`。

token 按每条消息的字符数除以 4 估算，整数除法，每条至少是 1。估算值大于 `budgetTokens` 才压缩。

压缩请求只包含最近 4 条轮次之前、角色不是 `tool` 的消息，顺序不变。一条轮次消息算一条，不把用户和助手配成一对。不足 4 条时，已有轮次都不进摘要。规则、技能、全部纠正 note、未完成任务板、`executionId` 和角色都不进摘要。旧工具结果也不进摘要，先留在轮次里。没有这种可摘要消息时，不调用模型。

有可摘要消息时，只调用一次模型。任务是 `LlmTask.COMPRESS`，点名的供应方是 null，`executionId` 是这次执行的编号。模型返回的正文成为摘要。已有的 `LlmRouter` 会把这种 `compress` 调用写入 `llm_call`。core 不再写一次，也不改路由。

摘要之后仍然超过预算时，把刚才留在最近 4 条之外的每条工具结果改成一行 `tool: [collapsed]`。最近 4 条里的工具结果保持原文。这一步之后仍然超过预算时，每条技能只保留名称和描述，正文改为空。先收工具结果，再降技能。收成一行之后已经不超过预算时，技能正文保持原样。两步之后仍然超过预算时，也不删纠正 note、未完成任务板和 `executionId`，规则和最近 4 条也不删。

`ToolMemory.reconcile` 看历史里最后一条角色为 `assistant` 的消息。英文双引号或中文弯引号 `“”` 里的片段，只要有一个不在工具结果里、也不在用户消息里，并且工具结果不是空白，就追加一条 `TOOL_CONTRADICTION` note。note 正文以 `以工具结果为准：` 开头，并带上工具结果全文。返回的新列表保留原来的助手消息，并在末尾附上角色为 `tool` 的工具结果全文。原来的历史列表不被修改。引号片段能在工具结果或用户消息里找到、助手消息没有引号片段、或者工具结果是空白时，不追加 note。工具结果是空白时，也不附加空的工具消息。

## 数据库

这次不改表，也不连接 JDBC。`correction_note` 和 `context_summary` 已在既有迁移里。note 写入用户目录，并交给执行快照的消费者。摘要正文放在 `ContextPrepared.summary`，供以后写入 `context_summary`。压缩调用的落库由已有的 compress 路由完成。

## 中间件

不访问外网，不启动 Spring，不使用消息中间件。压缩测试使用脚本化的 `ChatModel`。

## 测试要求

1. `ContextWindow.DEFAULT_BUDGET_TOKENS` 是 8000。
2. 预算为 8000 且上下文没有超出时，不调用模型，最后一条是当前用户消息，它前面的两条依次是传入的两条纠正 note。
3. 预算为 8000 且上下文没有超出时，旧工具结果和技能正文都保持原文，并且没有摘要消息。
4. 超过预算并完成压缩后，最后一条仍是当前用户消息，它前面的两条仍是那两条纠正 note。
5. 压缩后的上下文仍含有规则、技能名称、技能描述、未完成任务板、最近 4 条轮次和 `executionId`。
6. 超过预算且存在可摘要的旧轮次时，模型只被调用一次，任务是 `compress`，点名的供应方是 null，`executionId` 等于这次执行，准备结果里的摘要等于模型返回的正文。
7. 压缩请求的消息里没有规则、技能名称、技能描述、技能正文、纠正 note、未完成任务板、`executionId`、最近 4 条轮次和旧工具结果，并且含有被摘要的旧用户轮次。
8. 去掉最近 4 条之后没有非工具消息时，不调用模型；上下文仍超预算时，旧工具结果收成一行，技能正文在收成一行后仍超预算时被去掉。
9. 摘要之后仍然超过预算时，最近 4 条之外的每条工具结果都变成 `tool: [collapsed]`，原来的工具正文不再出现。
10. 最近 4 条之内的工具结果在收成一行时保持原文。
11. 收成一行之后仍然超过预算时，提示里有技能名称和描述，没有技能正文。
12. 收成一行之后没有超过预算时，技能正文仍在，旧工具结果已经变成 `tool: [collapsed]`。
13. 摘要、收成一行和降技能之后，预算仍然不够时，纠正 note、未完成任务板和 `executionId` 仍在。
14. 追加一条纠正 note 后，用户目录里的 JSON 含有 `noteId`、`sessionId`、`executionId`、`createdAt`、`source` 和 `text`，执行快照里是同一条 note。
15. `USER`、`TOOL_CONTRADICTION`、`GROUNDING`、`EVALUATION` 都能写入用户目录，枚举也只有这四个值。
16. note 为 null、字段为空白、`source` 或 `createdAt` 为 null，或者 `noteId`、`sessionId` 含有路径片段时，追加失败，错误码是 `INTERNAL`，不写文件，快照消费者不被调用。
17. 助手消息里的英文双引号或中文弯引号片段和工具结果冲突时，追加一条 `TOOL_CONTRADICTION`，返回列表末尾是工具结果全文，原来的历史列表长度不变。
18. 引号片段能在工具结果或用户消息里找到、助手消息没有引号，或者工具结果为空白时，不追加纠正 note。
19. `budgetTokens` 小于 1，或 `executionId` 为空白时，`prepare` 失败，错误码是 `INTERNAL`，不调用模型。
20. 没有 `ChatModel` 时，构造 `ContextWindow` 失败，错误码是 `INTERNAL`。

## 还没定

无。HTTP 追加、把摘要写入 `context_summary` 表、把 core 的窗口接进工作流的 ReAct，都留给后面的组装和 012。
