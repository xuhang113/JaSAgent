# 2026-11-08 会话控制台

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：会话成为控制台主页面。选中会话后能看到关联的子 agent 和纠正 note。CLI、SSE、通行证、溯源、评估默认关闭，点开后可以拖动宽度，并在旁边按 LangGraph 消息时间线查看 system、规则、技能描述、用户和 AI 对话、工具结果。上下文占用达到 `jasagent.context.compress-at`（默认 0.85）才请求模型压缩；压缩未完成时不把未完成摘要当成已发布结果。
- 涉及模块：`jasagent-web`、`jasagent-controller`、`jasagent-api`、`jasagent-app`、`jasagent-core`
- 怎么验证：`requirements/013-console-session/tests/013-console-session.sh` 29 条退出码 0。`ConsoleSessionPageTest` 25、`ConsolePageTest` 28、`AgentPassDisabledWebTest` 1、`ContextCompressGateTest` 4、`ContextCorrectionTest` 20 均通过，跳过 0。本机浏览器打开登录页、选定工作区、新建会话、追加纠正 note、拖动分隔、打开 CLI 与时间线、用 `/` 引用 skill、中止和恢复。
