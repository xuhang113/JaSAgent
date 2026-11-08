# 需求 013 会话控制台

**状态**: 已实现

## 这次要改什么

现在登录后的页面是 Vue 3 管理控制台。顶栏能选定工作区，左侧有会话列表，中间是 CLI 面板，右侧是对话。CLI 面板里的日志和 SSE 回显、页面下方的当前执行、工具、插件、通行证、检索溯源和评估，一打开就铺满，空的时候也占着大块空白。纠正 note 只在点开「上下文」抽屉后才能看见。会话上还没有这条会话关联的子 agent。面板宽度不能拖。上下文压缩写在 `jasagent-core` 的 `ContextWindow`：估算 token 超过 `budgetTokens` 才调用模型，没有 `jasagent.context.compress-at`，压缩进行中也没有一份只在模型完整返回后才发布的结果。

这次把会话做成主管理页。左侧仍是当前用户的会话列表。选中一条会话后，主区域展示这条会话关联的子 agent 和纠正 note（harness note）。CLI、SSE、通行证、溯源、评估这些面板默认不展开，用户点开才出现，宽度可以拖。点开其中一个面板后，旁边出现二级面板，按 LangGraph 的消息时间线展示这次执行支持的内容：system、其中的规则和技能描述、human 与 ai 的对话、tool 的调用结果。消息区不再留 180px 的最低高度，也不再默认铺开标题为「当前执行」的空块。

上下文仍按字符数除以 4 估算占用。占用达到 `jasagent.context.compress-at` 才请求模型做压缩，默认 0.85。占用低于这个比例时不调用模型。压缩还没完成时，已发布的摘要保持上一次已经完成的内容，不能把尚未返回的摘要当成最终结果。模型完整返回后，已发布摘要才换成这次的完整正文。超过预算之后，旧工具结果收成一行、技能正文降成名称和描述，这两步的顺序不变。纠正 note、未完成任务板和 executionId 仍然不删除。

登录页、工作区选定、中止、恢复、SSE、对话里输入 `/` 引用 skill、通行证的 ALLOW 和 DENY 都保留，只是后几项不再默认铺满全屏。不改命令行，不改未登录 401 的语义，不新增数据库表，不使用 Spring AI，不把密钥写进仓库。

## 模块

| 模块 | 现在负责什么 |
| --- | --- |
| jasagent-web | 把 Vue 构建出的页面交给 Spring |
| jasagent-controller | 登录后的控制台 REST |
| jasagent-api | 控制台端口 |
| jasagent-app | 把执行、会话和上下文装到一起 |
| jasagent-core | 上下文窗口和压缩 |

## 改动地方

**jasagent-web**

`frontend/src/views/ConsoleView.vue` 现在把会话、CLI、对话和整块执行详情同时摆开。这次改成会话主区域：`session-main` 里列出子 agent 和纠正 note。CLI、SSE、通行证、溯源、评估放进默认关闭的面板，根节点标记 `data-default-panels="hidden"` 和 `data-panels-open="false"`。分隔条使用 `data-resize`。点开面板后，二级面板用 `data-timeline="langgraph"` 按 system、human、ai、tool 的顺序渲染消息。`frontend/src/styles.css` 把 `.messages` 的最低高度从 180px 去掉，并加上可拖分隔和 `.lg-timeline`。构建结果仍写到 `src/main/resources/static`，Spring 继续托管这份静态文件。

**jasagent-controller**

`ConsoleController` 已经提供会话、纠正 note 和上下文接口。这次增加 `GET /api/sessions/{id}/timeline`。未登录仍然 401。

**jasagent-api**

`ConsoleApi` 增加按会话读取时间线的方法。已有的会话、消息、纠正 note 和通行证方法保持原样。

**jasagent-app**

`LocalRuntime` 在会话消息入队后调度一次，把角色为 SUB 的子 agent 记到该用户目录的会话文件里。`session` 的响应带上这些子 agent，并带上该会话已经追加的纠正 note。执行过程中按 LangGraph 消息类型记下 system、human、ai、tool。system 正文含有这次装载的规则和技能描述。tool 正文是这次工具返回的文本。`application.properties` 增加 `jasagent.context.compress-at=0.85`。`JasagentApplication` 把这个值交给运行时。上下文接口的响应带上 `compressAt`。

**jasagent-core**

`ContextWindow` 增加默认阈值 0.85。`prepare` 在占用达到阈值时才调用 `compress`。占用不超过预算但已经达到阈值时也调用。调用尚未完成时，`published` 仍是上一次完成的结果。完整正文返回后才发布新的摘要。

## 数据库

执行记录仍然在工作区的 H2 文件里，迁移里已经有 `execution`、`agent`、`message`、`correction_note` 和 `context_summary`。这次不加表，也不改这些表的列。会话和子 agent 列表写在该用户目录的 `conversations/`。纠正 note 仍写在该用户目录的 `corrections/{sessionId}/`。时间线跟这次执行留在进程里，供当前进程的页面读取。

## 中间件

HTTP 仍只绑定 `127.0.0.1:18721`。不新增消息队列或缓存。调度仍用现有的 `QueueAgentScheduler`。压缩仍通过已有的 `ChatModel.complete` 发出任务 `compress`，不新增远程通道。默认测试不访问外网，也不调用真实模型。

## 测试要求

按下面几条验收。每条单独做，做完就能判断过不过。

1. 未登录读取 `/api/sessions` 得到 401，响应体含 `errorCode` 且不含堆栈。
2. 控制台脚本含有 `session-main` 和「子 agent」。
3. 控制台脚本含有「纠正 note」和 `harness note`。
4. 控制台脚本含有 `data-default-panels":"hidden"`。
5. 控制台脚本含有 `data-resize`。
6. 用户为自己的会话发送消息后，读取该会话能看到 `"role":"SUB"`。
7. 用户为自己的会话追加纠正 note 后，读取该会话能看到这段文字。
8. 另一用户读取该会话得到 401。
9. 时间线里类型为 system 的消息正文含有这次写入的规则描述。
10. 同一条 system 消息正文含有这次写入的技能描述。
11. 时间线含有类型为 human 的用户原文。
12. 没配模型时，时间线里的 ai 写明「模型未配置」，不把「已收到」当成完成。
13. 时间线含有类型为 tool、名称为 clock 的消息，正文是这次时钟工具返回的时间文本。
14. 时间线里 system、human、tool、ai 按这个顺序出现。
15. 另一用户读取该时间线得到 401。
16. `ContextWindow.DEFAULT_COMPRESS_AT` 是 0.85。
17. 占用低于 0.85 时不调用模型。
18. 占用达到 0.85 且估算 token 不超过预算时，模型只被调用一次，任务是 compress，准备结果的摘要等于模型返回的完整正文。
19. 压缩尚未完成时，已发布摘要仍是上一次完成的空摘要；模型返回完整正文后，已发布摘要等于该正文，并且不再处于压缩中。
20. `application.properties` 含有 `jasagent.context.compress-at=0.85`。
21. 会话上下文接口的正文含有 `compressAt`，数值是 0.85。
22. 控制台脚本含有「中止」。
23. 控制台脚本含有「恢复」。
24. 控制台脚本含有 `SSE`。
25. 控制台脚本含有 `/skill:`。
26. 控制台脚本含有「通行证」。
27. 构建出的样式里 `.messages` 规则不含 `180px`。
28. 控制台脚本含有 `data-timeline":"langgraph"`。
29. 控制台脚本不含「当前执行」。

## 还没定

没有还没定。
