# 005 工具、插件和通行证

## 这次要改什么

现状是仓库已经能创建本机用户、校验口令，并解析用户目录和工作区目录。错误码只有 `AUTH` 和 `INTERNAL`。还没有工具目录，没有进程内插件，也没有外部能力执行前的通行证。父工程只登记了 `jasagent-api` 和 `jasagent-auth`。

这次补上三件事。工具目录负责登记和调用，同名工具在登记时失败。内置 `clock` 返回 UTC 时间，它是内部工具，不申请通行证。外部工具和插件在真正执行之前调用 api 里的 `PassGate.acquire`。`jasagent-agentpass` 按该用户目录的 `auth/policies.yaml` 做本用户策略：命中则返回 `ALLOW` 或 `DENY`，未命中返回 `PENDING`，reason 为 `approval-required`，不执行该次调用，并把该执行状态记为 `SUSPENDED`。`DENY` 不执行。已登录用户只能审批自己的待审批，批准为 `ALLOW` 后用原来的 `executionId` 执行该次调用，拒绝为 `DENY` 时不执行。`SKIPPED` 只在显式配置 `jasagent.agentpass.enabled=false` 时出现，reason 为 `agentpass-not-enabled`，这时才执行。缺省 `enabled=true`。工作区里的策略文件不能放宽用户策略。

不改登录、口令和目录规则，不改模型路由，不改 workflow 的图怎么走。这三个模块不使用 Spring、Spring AI、LangChain4j 或 langgraph4j，不手写图执行器、检查点存储或图上的 trace。页面、REST 和命令行这次不实现，以后由它们调用这里的 `PassGate`。不做跨用户审批，不做单独的策略管理站。

## 模块

`jasagent-tool` 实现工具目录和调用。`jasagent-plugin` 用 `ServiceLoader` 装载进程内插件并异步调用，附带 `grounding.default`。`jasagent-agentpass` 实现 `PassGate`。`jasagent-tool` 和 `jasagent-plugin` 的 Maven 依赖只指向 `jasagent-api`，只调用 `PassGate` 接口，不引用 `dev.jasagent.agentpass` 里的类。`jasagent-agentpass` 也不引用 `dev.jasagent.tool` 或 `dev.jasagent.plugin`。

工具、插件和通行证的端口放在 `jasagent-api`，因为除组装模块外业务模块只能依赖 api。这次会把上述三个模块登记进父工程的反应器，并给错误码补上 `TOOL` 和 `PLUGIN`。不改 `jasagent-auth` 的生产代码。

## 改动地方

`PassRequest` 带 `userId`、`executionId`、用户的 jasagent 目录、工作区的 jasagent 目录、工具名或插件 id、参数哈希。`Pass.decision` 只允许 `ALLOW`、`DENY`、`SKIPPED`、`PENDING`。`userDirectory` 指向 `${user.home}/<登录名>/jasagent` 这一层，策略文件固定是该目录下的 `auth/policies.yaml`。实现不读取 `workspaceDirectory` 下面的任何策略文件。

策略文件只支持这一种写法。`rules:` 开头，接着若干条。每一条先写 `- tool: 名字` 或 `- plugin: 标识`，下一行写 `decision: ALLOW` 或 `decision: DENY`。名字和标识精确匹配，区分大小写。同一目标有多条规则时，采用文件中靠前的那一条。空文件或只有注释视为没有规则。文件存在但不符合这个写法时，`acquire` 以错误码 `INTERNAL` 失败，不返回 `ALLOW`。工具名只匹配带 `tool` 的规则，插件 id 只匹配带 `plugin` 的规则。

参数哈希使用 SHA-256，输出小写十六进制。工具参数是字符串映射，按键名排序后拼成 `key=value`，行与行之间用换行连接，再取哈希。插件把阶段名、参数字符串、模型输出三段用换行连接后再取哈希。测试用独立的 `MessageDigest` 重算，不调用产品里的哈希方法。

`jasagent.agentpass.enabled` 缺省、空白或 `true` 时开启策略。值为 `false` 时关闭策略，`acquire` 直接返回 `SKIPPED`，不读策略文件，也不把执行标成 `SUSPENDED`。开启策略且命中 `ALLOW` 或 `DENY` 时，reason 为 `policy`。命中 `DENY` 时不把执行标成 `SUSPENDED`。

未命中时，`PassGate` 把该 `executionId` 的状态记为 `SUSPENDED`，这个状态保存在当前实例里。同时在该用户目录 `auth/pending/<passId>` 写入待审批，权限仅所有者可读写。`pending(userId, userDirectory)` 只返回该用户自己的 `PENDING` 记录。另一用户即使指到这个目录，也读不到这些记录。`decide` 只接受 `ALLOW` 或 `DENY`。审批者的 `userId` 或 `executionId` 与记录不一致时，以错误码 `AUTH` 失败，记录保持 `PENDING`。已经不是 `PENDING` 的通行证再次审批时，以错误码 `INTERNAL` 失败，原决定不变。

工具目录在构造时拒绝同名工具，错误码为 `TOOL`。名为 `clock` 的内置工具返回 `Instant` 可解析的 UTC 文本，调用路径不进入 `acquire`。其他工具先 `acquire`，再按决定执行：`ALLOW` 和 `SKIPPED` 执行工具体，`DENY` 和 `PENDING` 不执行。每次决定追加一条 `tool_call`，未执行时 `executed` 为假。批准挂起的调用时，目录用当初保存的那一次调用执行，结果里的 `executionId` 不变。目录不对两次调用加全局锁，所以两个外部调用可以同时停在 `acquire` 上。

插件宿主在构造时拒绝同名插件 id，错误码为 `PLUGIN`。`ServiceLoader` 能装到 `grounding.default`。宿主先 `acquire`，`DENY` 和 `PENDING` 不进入插件体，`ALLOW` 和 `SKIPPED` 才进入。`grounding.default` 只在 `afterModel` 调用 `HallucinationGuard.check`，并把 `executionId` 和模型输出传进去；其他阶段不调用守卫。插件体抛错时，`failure` 不是 `FAIL` 就记录后正常返回，`failed` 为真、`executed` 为假；`failure` 为 `FAIL` 时本次调用以错误码 `PLUGIN` 失败。每次决定追加一条 `plugin_call`。挂起后再批准或拒绝的规则与工具相同。

`tool_call` 和 `plugin_call` 先写在进程内的记录端口。挂起调用的参数留在当前工具目录或插件宿主的进程内存里，同一进程内批准后执行。阻塞的文件读写放进 `VirtualIo`。

## 数据库

这次不建表，不连接 JDBC。执行状态的数据库行、以及 `tool_call` 和 `plugin_call` 的数据库行，留给持久化需求。这次的待审批落在用户目录文件里。

## 中间件

不使用消息中间件，不监听 HTTP，不启动 Spring。插件发现使用 JDK 的 `ServiceLoader`。

## 测试要求

1. 同名工具在目录构造时失败，错误码为 `TOOL`。
2. `clock` 的输出能被解析成 UTC 瞬时，并且这次调用没有进入 `acquire`。
3. 外部工具在工具体执行前调用 `acquire`，请求中的 `userId`、`executionId`、工具名、用户目录和工作区目录与这次调用一致，此时工具体还没有执行。
4. 外部工具的参数哈希等于测试用独立 `MessageDigest` 对排序后的 `key=value` 行计算的 SHA-256 小写十六进制。
5. 通行证为 `DENY` 时工具体不执行，结果中的决定是 `DENY`。
6. 通行证为 `ALLOW` 时工具体执行，结果带回工具体的输出。
7. 通行证为 `PENDING` 时工具体不执行。
8. 通行证为 `SKIPPED` 时工具体执行。
9. 挂起的工具被同一用户批准为 `ALLOW` 后，工具体执行，结果中的 `executionId` 仍是原来的那一个。
10. 挂起的工具被同一用户拒绝为 `DENY` 后，工具体不执行。
11. `DENY` 和 `PENDING` 都会写入该 `executionId` 的 `tool_call`，并且 `executed` 为假。
12. 两个外部工具调用可以同时停在 `acquire` 上，目录不会把第二次调用排到第一次完全结束之后。
13. 配置里没有 `jasagent.agentpass.enabled` 时视为开启，值为 `true` 时视为开启，值为 `false` 时视为关闭。
14. 通行证关闭时，`acquire` 返回 `SKIPPED`，reason 为 `agentpass-not-enabled`，执行状态不是 `SUSPENDED`。
15. 用户策略把工具名匹配为 `ALLOW` 时，`acquire` 返回 `ALLOW`。
16. 用户策略把工具名匹配为 `DENY` 时，`acquire` 返回 `DENY`，执行状态不是 `SUSPENDED`。
17. 用户策略把插件 id 匹配为 `DENY` 时，`acquire` 返回 `DENY`。
18. 同一工具名有多条规则时，采用文件中靠前的那一条。
19. 没有命中规则时，`acquire` 返回 `PENDING`，reason 为 `approval-required`。
20. 没有命中规则时，该 `executionId` 的执行状态变为 `SUSPENDED`。
21. 没有命中规则时，该用户能从自己的目录读回这条待审批，另一用户读不到；记录文件权限仅所有者可读写；新的 `PassGate` 实例从该目录仍能读到同一 `passId`。
22. 另一用户提交 `ALLOW` 时失败，错误码为 `AUTH`，待审批仍是 `PENDING`。
23. 审批请求中的 `executionId` 与记录不一致时失败，错误码为 `AUTH`，待审批仍是 `PENDING`。
24. 工作区目录中的 `ALLOW` 不能覆盖用户目录中的 `DENY`，也不能把用户目录里没有命中的工具变成 `ALLOW`。
25. 两个用户目录中的策略互不影响。
26. 策略文件存在但无法按约定解析时，`acquire` 以错误码 `INTERNAL` 失败。
27. 已经不是 `PENDING` 的通行证再次审批时，以错误码 `INTERNAL` 失败，原决定不变。
28. `ServiceLoader` 的装载结果包含 `grounding.default`。
29. `grounding.default` 在 `afterModel` 调用 `HallucinationGuard`，并传入这次的 `executionId` 和模型输出。
30. `grounding.default` 在 `beforeModel` 不调用 `HallucinationGuard`。
31. 插件在进入插件体之前调用 `acquire`，请求中的 `userId`、`executionId` 和插件 id 与这次调用一致，此时插件体还没有执行。
32. 插件的参数哈希等于测试用独立 `MessageDigest` 对阶段名、参数字符串和模型输出三段计算的 SHA-256 小写十六进制。
33. 通行证为 `DENY` 时不进入插件体。
34. 通行证为 `PENDING` 时不进入插件体。
35. 插件体失败且 `failure` 不是 `FAIL` 时，调用正常返回，`failed` 为真，`executed` 为假。
36. `failure` 为 `FAIL` 时，插件体失败使这次调用以错误码 `PLUGIN` 失败。
37. 通行证为 `SKIPPED` 时插件体执行。
38. 同名插件在宿主构造时失败，错误码为 `PLUGIN`。
39. 挂起的插件被批准为 `ALLOW` 后才进入插件体，结果中的 `executionId` 仍是原来的那一个。
40. 挂起的插件被拒绝为 `DENY` 后不进入插件体。
41. `DENY` 和 `PENDING` 都会写入该 `executionId` 的 `plugin_call`，并且 `executed` 为假。
42. `jasagent-tool`、`jasagent-plugin`、`jasagent-agentpass` 的构建文件和 Java 源文件不依赖 Spring、Spring AI 或 langgraph；tool 与 plugin 不引用 agentpass 的实现包；agentpass 不引用 tool 与 plugin 的实现包。

## 还没定

无。策略文件只支持上一节写明的子集。工具参数只接受字符串映射。进程退出之后如何重放挂起调用的参数，留给持久化和恢复，这次只保证同一进程内批准后执行。
