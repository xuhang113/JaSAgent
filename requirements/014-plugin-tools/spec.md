# 需求 014 外部插件工具

**状态**: 草稿

## 这次要改什么

现在工具目录能登记工具并调用。名为 `clock` 的内置工具返回 UTC 时间，调用时不进入 `PassGate.acquire`。其它外部工具在工具体执行前申请通行证。插件宿主用 `ServiceLoader` 装载 `AgentPlugin`，执行前同样申请通行证。检索只读已经放在 scope 目录 `rag/docs` 里的 md 和 txt。仓库里还没有 `web.search`、`rag.link`、`github.link`，也没有按工具名累计成功和失败次数的统计接口。

这次补上外部插件工具，并用同一套 Java SPI 登记工具和统计。统计接口和插件工具接口都定义在 `jasagent-api`。实现类放进 `META-INF/services`，由 `ServiceLoader` 装载。工具目录、插件宿主、外部插件工具目录都只调用这一个统计接口。不另建计数器，也不把 `tool_call` 或 `plugin_call` 的条数当作这组使用次数。

这次改成这几件事。

1. 用 `ServiceLoader` 装载外部插件工具。至少提供 `web.search`、`rag.link`、`github.link`，名字保持这三个字面量。同名工具在目录构造时失败，错误码为 `TOOL`。
2. 调用这些外部工具之前先执行 `PassGate.acquire`。只有 `ALLOW` 和 `SKIPPED` 才进入工具体。`DENY` 和 `PENDING` 不进入。
3. 工具体或插件体正常完成时，该名字的成功次数加 1。工具体或插件体抛错时，该名字的失败次数加 1，并且只加这一次。`DENY` 和 `PENDING` 不加减次数。`clock` 仍不申请通行证，也不自动计入这组次数；调用方直接调用统计接口时才记下 `clock`。

不改登录、口令和目录规则，不改 workflow 的图，不改控制台，不改 `module/console-session`。不改 `clock` 跳过通行证的路径。不改 `grounding.default` 只在 `afterModel` 调用守卫的条件。不新增 Maven 模块。不写密钥，不访问外网的测试默认路径。

## 模块

`jasagent-api` 放插件工具端口和统计端口。`jasagent-plugin` 用 `ServiceLoader` 提供三个外部工具，并在插件宿主里把插件调用记入同一个统计端口。`jasagent-tool` 在现有工具目录里把外部工具体的成功和失败记入同一个统计端口，`clock` 仍走原来的内部路径。这两个实现模块的 Maven 依赖仍只指向 `jasagent-api`，不引用 `dev.jasagent.agentpass`。

| 模块 | 现在负责什么 |
| --- | --- |
| jasagent-api | 工具、插件和通行证的端口 |
| jasagent-plugin | 用 ServiceLoader 装载进程内插件并在执行前申请通行证 |
| jasagent-tool | 登记并调用工具，clock 不申请通行证 |

## 改动地方

**jasagent-api**

这个模块现在只有端口，没有 `ServiceLoader` 登记。这次增加插件工具接口 `PluginTool` 和统计接口 `CallStats`，以及次数记录 `CallCount`。`PluginTool` 提供 `ToolSpec` 和 `call(ToolCall)`。`CallStats` 提供 `recordSuccess(toolName)`、`recordFailure(toolName)`、`count(toolName)`，并提供 `load()`，用 `ServiceLoader` 装载唯一的实现。实现类是 `SharedCallStats`，登记在 `META-INF/services/dev.jasagent.api.plugin.CallStats`。所有实例共用同一份进程内计数，所以连续两次 `load()` 读到的是同一套次数。没有登记或登记了多份实现时，`load()` 以错误码 `PLUGIN` 失败。某个 `toolName` 还没有记录时，`count` 的成功次数和失败次数都是 0。

**jasagent-plugin**

这个模块现在装载 `AgentPlugin`。这次增加 `PluginToolCatalog`。`load(PassGate)` 用 `ServiceLoader` 装载 `PluginTool`，再交给目录构造。构造时按 `ToolSpec.name` 建表，同名抛出 `AgentException`，错误码为 `TOOL`。`call` 先按现有工具目录的方式计算参数哈希并 `acquire`。`PassRequest` 的 `toolName` 是工具名，`pluginId` 为空。`DENY` 和 `PENDING` 返回未执行的 `ToolResult`，不调用工具体，也不改统计。`ALLOW` 和 `SKIPPED` 才调用工具体。工具体正常返回时 `recordSuccess` 一次，`executed` 为真，输出为工具体文本。工具体抛错时 `recordFailure` 一次，然后让这次调用失败；原来的 `AgentException` 保持原错误码。

三个工具类是 `WebSearchTool`、`RagLinkTool`、`GithubLinkTool`，登记在 `META-INF/services/dev.jasagent.api.plugin.PluginTool`。它们都不是内部工具。

`web.search` 读取参数 `query`。没有 `query` 时工具体以错误码 `TOOL` 失败。默认搜索客户端不打开网络：查询 `jasagent` 时返回一行标题 `jasagent`、一行 `url` `https://example.invalid/search?q=jasagent`、一行摘要 `local:jasagent`，三行用换行连接，末尾不加空行。测试可以换入搜索客户端。每条结果都是 `title`、`url`、`summary` 三行。多条结果之间空一行。

`rag.link` 读取参数 `url` 和 `scope`。`scope` 只接受 `user` 或 `workspace`，分别写到调用里的用户目录或工作区目录下的 `rag/docs`。没有 `scope`、链接方案不是 `http` 或 `https`、或文件名不安全时，以错误码 `TOOL` 失败，并且不在 `rag/docs` 留下普通文件。文件名只取 URL 路径最后一段。这一段以 `.md` 或 `.txt` 结尾时保留它，忽略大小写。否则内容类型含 `markdown` 时补 `.md`，其它补 `.txt`。返回文本是 `uri: 文件名` 和 `docId: 文件名` 两行，中间用换行连接。文件内容等于响应正文。默认测试传入假的抓取结果，不打开套接字。无参构造使用 JDK `HttpClient`，测试里的真实抓取只访问 `127.0.0.1`。阻塞的抓取和写文件放在 `VirtualIo` 里。

`github.link` 接受参数 `repo` 或 `url`。两者都没有时以错误码 `TOOL` 失败。有 `url` 时解析 `url`，否则解析 `repo`。`owner/repo` 取两段。`http` 或 `https` 的主机名必须是 `github.com` 或 `www.github.com`，忽略大小写；仓库名去掉末尾的 `.git`。其它主机以错误码 `TOOL` 失败。URL 路径里的 `tree` 段不代替默认分支。默认客户端不打开网络，默认分支是 `main`。测试可以换入客户端。返回四行，用换行连接：`repository: owner/repo`、`defaultBranch: 分支`、`url: https://github.com/owner/repo`、`tree: https://github.com/owner/repo/tree/分支`。

`ServicePluginHost` 仍先申请通行证。插件体正常返回且 `failed` 为假时，把 `pluginId` 当作 `toolName` 调用一次 `recordSuccess`。插件体抛错时调用一次 `recordFailure`，随后仍按原来的 `failure` 规则返回或抛出 `PLUGIN`。`DENY` 和 `PENDING` 不记次数。

**jasagent-tool**

`DefaultToolCatalog` 里，`clock` 以及 `internal` 为真的工具仍直接执行，不调用 `acquire`，也不调用 `CallStats`。其它工具仍先 `acquire`。工具体正常返回时 `recordSuccess` 一次。工具体抛错时 `recordFailure` 一次，然后把原异常继续抛出。`DENY` 和 `PENDING` 不记次数。

## 数据库

仓库里现在没有为工具调用建表。这次不建表，不连接 JDBC。成功次数和失败次数只留在 `SharedCallStats` 的进程内计数。`rag.link` 写入的是 scope 目录里的普通文件。已有的待审批文件、`tool_call` 和 `plugin_call` 记录方式不改。

## 中间件

现在没有消息队列。插件发现已经使用 JDK 的 `ServiceLoader`。这次不加消息中间件，也不新增监听端口。外部插件工具和统计实现都继续用 `ServiceLoader`。`rag.link` 的无参抓取使用 JDK `HttpClient`。`web.search` 和 `github.link` 的默认客户端不打开网络。

## 测试要求

按下面几条验收。每条单独做，做完就能判断过不过。

1. 用 `ServiceLoader` 装载外部插件工具后，名字里同时有 `web.search`、`rag.link` 和 `github.link`。
2. 装载到的 `web.search`、`rag.link`、`github.link` 都不是内部工具。
3. 用两个名字都是 `web.search` 的外部插件工具构造目录时抛出 `AgentException`，错误码为 `TOOL`。
4. 默认 `web.search` 查询 `jasagent` 时，返回文本等于 `title: jasagent`、`url: https://example.invalid/search?q=jasagent`、`summary: local:jasagent` 这三行，行之间用换行连接。
5. 注入一条标题 `Alpha`、url `https://example.invalid/alpha`、摘要 `brief` 的搜索结果时，返回文本等于这三行，行之间用换行连接。
6. 注入上述一条和另一条标题 `Beta`、url `https://example.invalid/beta`、摘要 `more` 时，返回文本是两段三行，段之间空一行。
7. 进入 `PassGate.acquire` 的那一刻，`web.search` 工具体执行次数为 0。
8. `web.search` 这次 `acquire` 收到的 `toolName` 是 `web.search`。
9. `web.search` 这次 `acquire` 收到的 `userId` 与调用传入的 `userId` 相同。
10. `web.search` 这次 `acquire` 收到的 `executionId` 与调用传入的 `executionId` 相同。
11. `web.search` 这次 `acquire` 收到的 `pluginId` 为空。
12. `web.search` 这次 `acquire` 收到的用户目录与调用传入的用户目录相同。
13. `web.search` 这次 `acquire` 收到的工作区目录与调用传入的工作区目录相同。
14. 参数只有 `query=jasagent` 时，`acquire` 的参数哈希等于测试用独立 `MessageDigest` 对 `query=jasagent` 计算的 SHA-256 小写十六进制。
15. 通行证为 `DENY` 时，`web.search` 工具体执行次数为 0。
16. 通行证为 `PENDING` 时，`web.search` 工具体执行次数为 0。
17. 通行证为 `SKIPPED` 时，`web.search` 工具体执行次数为 1。
18. `rag.link` 用假响应把 `https://example.invalid/guide.md` 写到用户目录后，`rag/docs/guide.md` 的内容等于假响应正文。
19. 这次 `rag.link` 的返回文本等于 `uri: guide.md` 和 `docId: guide.md` 两行，行之间用换行连接。
20. `scope` 为 `workspace`、内容类型为 `text/plain`、链接为 `https://example.invalid/notes` 时，工作区 `rag/docs/notes.txt` 的内容等于假响应正文。
21. 链接 `file:///tmp/guide.md` 使这次 `rag.link` 以错误码 `TOOL` 失败。
22. 链接 `file:///tmp/guide.md` 调用结束后，用户目录 `rag/docs` 没有任何普通文件。
23. 假响应调用 `rag.link` 期间，代理选择器的 `select` 次数为 0。
24. 无参 `rag.link` 抓取 `127.0.0.1` 上内容类型为 `text/markdown` 的 `/guide.md` 后，用户目录 `rag/docs/guide.md` 的内容等于响应正文 `hello rag`。
25. `github.link` 接受 `acme/widgets` 时，返回文本等于 `repository: acme/widgets`、`defaultBranch: main`、`url: https://github.com/acme/widgets`、`tree: https://github.com/acme/widgets/tree/main` 四行，行之间用换行连接。
26. `github.link` 接受 `https://github.com/acme/widgets.git` 时，返回文本等于上一题的四行。
27. `github.link` 接受 `http://github.com/acme/widgets` 时，返回文本等于上一题的四行。
28. 注入的客户端把默认分支定为 `develop` 时，`github.link` 对 `acme/widgets` 的返回文本等于 `repository: acme/widgets`、`defaultBranch: develop`、`url: https://github.com/acme/widgets`、`tree: https://github.com/acme/widgets/tree/develop` 四行，行之间用换行连接。
29. 默认客户端下，`https://github.com/acme/widgets/tree/develop` 的返回文本仍等于 `main` 那四行。
30. `https://gitlab.com/acme/widgets` 使这次 `github.link` 以错误码 `TOOL` 失败。
31. 默认 `github.link` 调用期间，代理选择器的 `select` 次数为 0。
32. 默认 `web.search` 调用期间，代理选择器的 `select` 次数为 0。
33. `web.search` 成功调用一次后，按 `web.search` 查到的成功次数比调用前多 1。
34. 这次成功调用后，按 `web.search` 查到的失败次数与调用前相同。
35. `web.search` 工具体抛错一次后，按 `web.search` 查到的失败次数比调用前多 1。
36. 这次抛错后，按 `web.search` 查到的成功次数与调用前相同。
37. 插件 `echo.stats` 成功调用一次后，按 `echo.stats` 查到的成功次数比调用前多 1。
38. 插件体以 `FAIL` 抛错时，按该插件 id 查到的失败次数比调用前多 1。
39. 同一次 `FAIL` 调用的错误码为 `PLUGIN`。
40. 插件体抛错且 `failure` 不是 `FAIL` 时，按该插件 id 查到的失败次数比调用前多 1。
41. 调用 `clock` 之后，按 `clock` 查到的成功次数与调用前相同。
42. 调用 `clock` 之后，按 `clock` 查到的失败次数与调用前相同。
43. 调用 `clock` 时，`PassGate.acquire` 的次数为 0。
44. 调用方对 `clock` 执行一次 `recordSuccess` 后，按 `clock` 查到的成功次数比调用前多 1。
45. 在第一次装载上记录一次成功后，第二次 `ServiceLoader` 装载读到的成功次数比记录前多 1。
46. `web.search` 成功一次后，按 `github.link` 查到的成功次数与调用前相同。
47. 通行证为 `DENY` 的 `web.search` 调用后，按 `web.search` 查到的成功次数与调用前相同。
48. 通行证为 `DENY` 的 `web.search` 调用后，按 `web.search` 查到的失败次数与调用前相同。
49. 工具目录里的外部工具 `weather` 成功一次后，按 `weather` 查到的成功次数比调用前多 1。
50. 没有 `query` 时，`web.search` 以错误码 `TOOL` 失败。
51. 没有 `scope` 时，`rag.link` 以错误码 `TOOL` 失败。
52. 没有 `repo` 也没有 `url` 时，`github.link` 以错误码 `TOOL` 失败。
53. 插件通行证为 `DENY` 时，按该插件 id 查到的成功次数与调用前相同。
54. 插件通行证为 `DENY` 时，按该插件 id 查到的失败次数与调用前相同。
55. 链接 `https://example.invalid/../secret.md` 使这次 `rag.link` 以错误码 `TOOL` 失败。
56. 链接 `https://example.invalid/../secret.md` 调用结束后，用户目录 `rag/docs` 没有任何普通文件。

## 还没定

没有还没定。默认搜索结果、默认分支 `main`、返回行格式、文件名规则和计数规则按上一节实现。
