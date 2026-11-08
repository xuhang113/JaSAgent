# 测试规范 014

**来源**: 同目录 `spec.md` 的测试要求

## 用例

| 用例 | 来源 | 做什么 | 则 | 脚本 | 结果文件 | 日志文件 |
| --- | --- | --- | --- | --- | --- | --- |
| T-001 | 测试要求 1 | 用 ServiceLoader 装载外部插件工具 | 用 `ServiceLoader` 装载外部插件工具后，名字里同时有 `web.search`、`rag.link` 和 `github.link`。 | `tests/T-001` | `results/T-001.txt` | `logs/T-001.log` |
| T-002 | 测试要求 2 | 查看装载到的三个工具说明 | 装载到的 `web.search`、`rag.link`、`github.link` 都不是内部工具。 | `tests/T-002` | `results/T-002.txt` | `logs/T-002.log` |
| T-003 | 测试要求 3 | 用两个同名 web.search 构造目录 | 用两个名字都是 `web.search` 的外部插件工具构造目录时抛出 `AgentException`，错误码为 `TOOL`。 | `tests/T-003` | `results/T-003.txt` | `logs/T-003.log` |
| T-004 | 测试要求 4 | 用默认客户端查询 jasagent | 默认 `web.search` 查询 `jasagent` 时，返回文本等于 `title: jasagent`、`url: https://example.invalid/search?q=jasagent`、`summary: local:jasagent` 这三行，行之间用换行连接。 | `tests/T-004` | `results/T-004.txt` | `logs/T-004.log` |
| T-005 | 测试要求 5 | 注入一条搜索结果后调用 web.search | 注入一条标题 `Alpha`、url `https://example.invalid/alpha`、摘要 `brief` 的搜索结果时，返回文本等于这三行，行之间用换行连接。 | `tests/T-005` | `results/T-005.txt` | `logs/T-005.log` |
| T-006 | 测试要求 6 | 注入两条搜索结果后调用 web.search | 注入上述一条和另一条标题 `Beta`、url `https://example.invalid/beta`、摘要 `more` 时，返回文本是两段三行，段之间空一行。 | `tests/T-006` | `results/T-006.txt` | `logs/T-006.log` |
| T-007 | 测试要求 7 | 在通行证放行前观察 web.search | 进入 `PassGate.acquire` 的那一刻，`web.search` 工具体执行次数为 0。 | `tests/T-007` | `results/T-007.txt` | `logs/T-007.log` |
| T-008 | 测试要求 8 | 调用 web.search 并查看通行证请求 | `web.search` 这次 `acquire` 收到的 `toolName` 是 `web.search`。 | `tests/T-008` | `results/T-008.txt` | `logs/T-008.log` |
| T-009 | 测试要求 9 | 调用 web.search 并查看通行证请求 | `web.search` 这次 `acquire` 收到的 `userId` 与调用传入的 `userId` 相同。 | `tests/T-009` | `results/T-009.txt` | `logs/T-009.log` |
| T-010 | 测试要求 10 | 调用 web.search 并查看通行证请求 | `web.search` 这次 `acquire` 收到的 `executionId` 与调用传入的 `executionId` 相同。 | `tests/T-010` | `results/T-010.txt` | `logs/T-010.log` |
| T-011 | 测试要求 11 | 调用 web.search 并查看通行证请求 | `web.search` 这次 `acquire` 收到的 `pluginId` 为空。 | `tests/T-011` | `results/T-011.txt` | `logs/T-011.log` |
| T-012 | 测试要求 12 | 调用 web.search 并查看通行证请求 | `web.search` 这次 `acquire` 收到的用户目录与调用传入的用户目录相同。 | `tests/T-012` | `results/T-012.txt` | `logs/T-012.log` |
| T-013 | 测试要求 13 | 调用 web.search 并查看通行证请求 | `web.search` 这次 `acquire` 收到的工作区目录与调用传入的工作区目录相同。 | `tests/T-013` | `results/T-013.txt` | `logs/T-013.log` |
| T-014 | 测试要求 14 | 用独立 MessageDigest 计算 query=jasagent | 参数只有 `query=jasagent` 时，`acquire` 的参数哈希等于测试用独立 `MessageDigest` 对 `query=jasagent` 计算的 SHA-256 小写十六进制。 | `tests/T-014` | `results/T-014.txt` | `logs/T-014.log` |
| T-015 | 测试要求 15 | 通行证为 DENY 时调用 web.search | 通行证为 `DENY` 时，`web.search` 工具体执行次数为 0。 | `tests/T-015` | `results/T-015.txt` | `logs/T-015.log` |
| T-016 | 测试要求 16 | 通行证为 PENDING 时调用 web.search | 通行证为 `PENDING` 时，`web.search` 工具体执行次数为 0。 | `tests/T-016` | `results/T-016.txt` | `logs/T-016.log` |
| T-017 | 测试要求 17 | 通行证为 SKIPPED 时调用 web.search | 通行证为 `SKIPPED` 时，`web.search` 工具体执行次数为 1。 | `tests/T-017` | `results/T-017.txt` | `logs/T-017.log` |
| T-018 | 测试要求 18 | 用假响应调用 rag.link 写入用户目录 | `rag.link` 用假响应把 `https://example.invalid/guide.md` 写到用户目录后，`rag/docs/guide.md` 的内容等于假响应正文。 | `tests/T-018` | `results/T-018.txt` | `logs/T-018.log` |
| T-019 | 测试要求 19 | 用假响应调用 rag.link | 这次 `rag.link` 的返回文本等于 `uri: guide.md` 和 `docId: guide.md` 两行，行之间用换行连接。 | `tests/T-019` | `results/T-019.txt` | `logs/T-019.log` |
| T-020 | 测试要求 20 | 用 text/plain 假响应写入工作区 | `scope` 为 `workspace`、内容类型为 `text/plain`、链接为 `https://example.invalid/notes` 时，工作区 `rag/docs/notes.txt` 的内容等于假响应正文。 | `tests/T-020` | `results/T-020.txt` | `logs/T-020.log` |
| T-021 | 测试要求 21 | 用 file 链接调用 rag.link | 链接 `file:///tmp/guide.md` 使这次 `rag.link` 以错误码 `TOOL` 失败。 | `tests/T-021` | `results/T-021.txt` | `logs/T-021.log` |
| T-022 | 测试要求 22 | 用 file 链接调用 rag.link | 链接 `file:///tmp/guide.md` 调用结束后，用户目录 `rag/docs` 没有任何普通文件。 | `tests/T-022` | `results/T-022.txt` | `logs/T-022.log` |
| T-023 | 测试要求 23 | 用假响应调用 rag.link | 假响应调用 `rag.link` 期间，代理选择器的 `select` 次数为 0。 | `tests/T-023` | `results/T-023.txt` | `logs/T-023.log` |
| T-024 | 测试要求 24 | 用无参 rag.link 抓取 127.0.0.1 | 无参 `rag.link` 抓取 `127.0.0.1` 上内容类型为 `text/markdown` 的 `/guide.md` 后，用户目录 `rag/docs/guide.md` 的内容等于响应正文 `hello rag`。 | `tests/T-024` | `results/T-024.txt` | `logs/T-024.log` |
| T-025 | 测试要求 25 | 用 owner/repo 调用 github.link | `github.link` 接受 `acme/widgets` 时，返回文本等于 `repository: acme/widgets`、`defaultBranch: main`、`url: https://github.com/acme/widgets`、`tree: https://github.com/acme/widgets/tree/main` 四行，行之间用换行连接。 | `tests/T-025` | `results/T-025.txt` | `logs/T-025.log` |
| T-026 | 测试要求 26 | 用带 .git 的 GitHub URL 调用 github.link | `github.link` 接受 `https://github.com/acme/widgets.git` 时，返回文本等于上一题的四行。 | `tests/T-026` | `results/T-026.txt` | `logs/T-026.log` |
| T-027 | 测试要求 27 | 用 http GitHub URL 调用 github.link | `github.link` 接受 `http://github.com/acme/widgets` 时，返回文本等于上一题的四行。 | `tests/T-027` | `results/T-027.txt` | `logs/T-027.log` |
| T-028 | 测试要求 28 | 注入默认分支 develop 后调用 github.link | 注入的客户端把默认分支定为 `develop` 时，`github.link` 对 `acme/widgets` 的返回文本等于 `repository: acme/widgets`、`defaultBranch: develop`、`url: https://github.com/acme/widgets`、`tree: https://github.com/acme/widgets/tree/develop` 四行，行之间用换行连接。 | `tests/T-028` | `results/T-028.txt` | `logs/T-028.log` |
| T-029 | 测试要求 29 | 用带 tree 段的 GitHub URL 调用 github.link | 默认客户端下，`https://github.com/acme/widgets/tree/develop` 的返回文本仍等于 `main` 那四行。 | `tests/T-029` | `results/T-029.txt` | `logs/T-029.log` |
| T-030 | 测试要求 30 | 用 gitlab URL 调用 github.link | `https://gitlab.com/acme/widgets` 使这次 `github.link` 以错误码 `TOOL` 失败。 | `tests/T-030` | `results/T-030.txt` | `logs/T-030.log` |
| T-031 | 测试要求 31 | 用默认客户端调用 github.link | 默认 `github.link` 调用期间，代理选择器的 `select` 次数为 0。 | `tests/T-031` | `results/T-031.txt` | `logs/T-031.log` |
| T-032 | 测试要求 32 | 用默认客户端调用 web.search | 默认 `web.search` 调用期间，代理选择器的 `select` 次数为 0。 | `tests/T-032` | `results/T-032.txt` | `logs/T-032.log` |
| T-033 | 测试要求 33 | 成功调用一次 web.search | `web.search` 成功调用一次后，按 `web.search` 查到的成功次数比调用前多 1。 | `tests/T-033` | `results/T-033.txt` | `logs/T-033.log` |
| T-034 | 测试要求 34 | 成功调用一次 web.search | 这次成功调用后，按 `web.search` 查到的失败次数与调用前相同。 | `tests/T-034` | `results/T-034.txt` | `logs/T-034.log` |
| T-035 | 测试要求 35 | 让 web.search 工具体抛错 | `web.search` 工具体抛错一次后，按 `web.search` 查到的失败次数比调用前多 1。 | `tests/T-035` | `results/T-035.txt` | `logs/T-035.log` |
| T-036 | 测试要求 36 | 让 web.search 工具体抛错 | 这次抛错后，按 `web.search` 查到的成功次数与调用前相同。 | `tests/T-036` | `results/T-036.txt` | `logs/T-036.log` |
| T-037 | 测试要求 37 | 成功调用插件 echo.stats | 插件 `echo.stats` 成功调用一次后，按 `echo.stats` 查到的成功次数比调用前多 1。 | `tests/T-037` | `results/T-037.txt` | `logs/T-037.log` |
| T-038 | 测试要求 38 | 让插件以 FAIL 抛错 | 插件体以 `FAIL` 抛错时，按该插件 id 查到的失败次数比调用前多 1。 | `tests/T-038` | `results/T-038.txt` | `logs/T-038.log` |
| T-039 | 测试要求 39 | 让插件以 FAIL 抛错 | 同一次 `FAIL` 调用的错误码为 `PLUGIN`。 | `tests/T-039` | `results/T-039.txt` | `logs/T-039.log` |
| T-040 | 测试要求 40 | 让插件体抛错且 failure 不是 FAIL | 插件体抛错且 `failure` 不是 `FAIL` 时，按该插件 id 查到的失败次数比调用前多 1。 | `tests/T-040` | `results/T-040.txt` | `logs/T-040.log` |
| T-041 | 测试要求 41 | 调用 clock | 调用 `clock` 之后，按 `clock` 查到的成功次数与调用前相同。 | `tests/T-041` | `results/T-041.txt` | `logs/T-041.log` |
| T-042 | 测试要求 42 | 调用 clock | 调用 `clock` 之后，按 `clock` 查到的失败次数与调用前相同。 | `tests/T-042` | `results/T-042.txt` | `logs/T-042.log` |
| T-043 | 测试要求 43 | 调用 clock | 调用 `clock` 时，`PassGate.acquire` 的次数为 0。 | `tests/T-043` | `results/T-043.txt` | `logs/T-043.log` |
| T-044 | 测试要求 44 | 调用方对 clock 执行 recordSuccess | 调用方对 `clock` 执行一次 `recordSuccess` 后，按 `clock` 查到的成功次数比调用前多 1。 | `tests/T-044` | `results/T-044.txt` | `logs/T-044.log` |
| T-045 | 测试要求 45 | 连续两次 ServiceLoader 装载统计实现 | 在第一次装载上记录一次成功后，第二次 `ServiceLoader` 装载读到的成功次数比记录前多 1。 | `tests/T-045` | `results/T-045.txt` | `logs/T-045.log` |
| T-046 | 测试要求 46 | 成功调用一次 web.search | `web.search` 成功一次后，按 `github.link` 查到的成功次数与调用前相同。 | `tests/T-046` | `results/T-046.txt` | `logs/T-046.log` |
| T-047 | 测试要求 47 | 以 DENY 调用 web.search | 通行证为 `DENY` 的 `web.search` 调用后，按 `web.search` 查到的成功次数与调用前相同。 | `tests/T-047` | `results/T-047.txt` | `logs/T-047.log` |
| T-048 | 测试要求 48 | 以 DENY 调用 web.search | 通行证为 `DENY` 的 `web.search` 调用后，按 `web.search` 查到的失败次数与调用前相同。 | `tests/T-048` | `results/T-048.txt` | `logs/T-048.log` |
| T-049 | 测试要求 49 | 成功调用工具目录里的 weather | 工具目录里的外部工具 `weather` 成功一次后，按 `weather` 查到的成功次数比调用前多 1。 | `tests/T-049` | `results/T-049.txt` | `logs/T-049.log` |
| T-050 | 测试要求 50 | 不带 query 调用 web.search | 没有 `query` 时，`web.search` 以错误码 `TOOL` 失败。 | `tests/T-050` | `results/T-050.txt` | `logs/T-050.log` |
| T-051 | 测试要求 51 | 不带 scope 调用 rag.link | 没有 `scope` 时，`rag.link` 以错误码 `TOOL` 失败。 | `tests/T-051` | `results/T-051.txt` | `logs/T-051.log` |
| T-052 | 测试要求 52 | 不带 repo 和 url 调用 github.link | 没有 `repo` 也没有 `url` 时，`github.link` 以错误码 `TOOL` 失败。 | `tests/T-052` | `results/T-052.txt` | `logs/T-052.log` |
| T-053 | 测试要求 53 | 以 DENY 调用插件 | 插件通行证为 `DENY` 时，按该插件 id 查到的成功次数与调用前相同。 | `tests/T-053` | `results/T-053.txt` | `logs/T-053.log` |
| T-054 | 测试要求 54 | 以 DENY 调用插件 | 插件通行证为 `DENY` 时，按该插件 id 查到的失败次数与调用前相同。 | `tests/T-054` | `results/T-054.txt` | `logs/T-054.log` |
| T-055 | 测试要求 55 | 用含 .. 的链接调用 rag.link | 链接 `https://example.invalid/../secret.md` 使这次 `rag.link` 以错误码 `TOOL` 失败。 | `tests/T-055` | `results/T-055.txt` | `logs/T-055.log` |
| T-056 | 测试要求 56 | 用含 .. 的链接调用 rag.link | 链接 `https://example.invalid/../secret.md` 调用结束后，用户目录 `rag/docs` 没有任何普通文件。 | `tests/T-056` | `results/T-056.txt` | `logs/T-056.log` |

## 不能生成

没有不能生成。

## 结果

`results/<用例编号>.txt` 只在脚本真实执行后出现。一行，制表符分开：

用例编号	通过或失败	则	耗时毫秒	日志文件

## 执行日志

`logs/<用例编号>.log` 只在脚本真实执行后出现。一行一个检查点，制表符分开：

时间	用例编号	检查点	耗时毫秒	可观察结果

