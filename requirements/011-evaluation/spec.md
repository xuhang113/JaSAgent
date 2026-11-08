# 011 轨迹和输出评估

## 这次要改什么

现状是登录、目录和模型路由已经可用。模型端口是 `ChatModel`，`RoutingChatModel` 按任务在 DeepSeek 和 AI Gateway 之间选择供应方。还没有评估器，执行终态之后也不会检查收尾文本。这次新增轨迹和输出评估。不改登录、口令、目录装载、模型路由的选择规则、工作流引擎、工具执行、检索、持久化表、trace 文件和页面。不引入 Spring，也不引入 Spring AI。本模块不直接访问 DeepSeek 或 Gateway，评判只把 `JUDGE` 任务交给已经存在的 `ChatModel`。

## 模块

这次新增 `jasagent-evaluation`。它的 Maven 依赖只指向 `jasagent-api`。父工程的模块列表增加 `jasagent-evaluation`，不改其他依赖管理。不修改 `jasagent-llm` 的实现类。

## 改动地方

内置评估器标识是 `completion.nonempty`、`tool.success.rate` 和 `llm.judge`。调用方传入执行 id、评估器标识、`required`、当前执行状态、收尾文本，以及工具调用轨迹。轨迹里每一项有工具名和是否成功。公共方法返回 `CompletionStage`。本模块没有阻塞的文件或网络读写，本地评估直接完成，评判则衔接 `ChatModel.complete` 的完成阶段。

`completion.nonempty` 看收尾文本。去掉首尾空白后仍有字符则通过，得分为 1，原因是 `nonempty`。文本为 null、空串或只有空白则不通过，得分为 0，原因是 `empty`。这个评估器不调用模型。

`tool.success.rate` 统计轨迹里的工具调用。得分等于成功次数除以总次数。没有工具调用时得分为 1。得分等于 1 才通过，原因写成 `success=<成功次数>;total=<总次数>`。任意一次失败则不通过，得分是上述比值。这个评估器不调用模型。

`llm.judge` 只有配置 `jasagent.evaluation.judge-enabled` 的值恰好是 `true` 时才启用。缺省、空串、`false`、`TRUE` 或其他值都视为关闭。关闭时不调用 `ChatModel`，评估不通过，得分为 0，原因是 `judge-disabled`。启用时调用注入的 `ChatModel.complete`。请求的任务是 `JUDGE`，`namedProvider` 为空，不在本模块里点名 `deepseek` 或 `ai-gateway`，工具列表为空，执行 id 原样传入。消息第一条是 system，第二条是 user。user 正文包含收尾文本，以及每条工具的名称和 `success` 或 `failed`。模型应只返回一个 JSON 对象，字段为 `passed`、`score`、`reason`。`passed` 是布尔，`score` 是 0 到 1 的数，`reason` 是字符串。字段顺序可以不同。解析成功时，评估结果采用这三个字段。正文无法按这个对象解析，或 `score` 不是 0 到 1 的有限数时，评估不通过，得分为 0，原因是 `judge-unparseable`，完成阶段的结果对象不是 null。`ChatModel` 自身失败时，评估的完成阶段带上原来的异常，不把异常收成 null。

未知评估器标识不通过，得分为 0，原因是 `unknown-evaluator`，并且不调用模型。

`required` 为 true 且这次评估不通过时，返回的执行状态改为 `FAILED`。`required` 不是 true 时，评估不通过不把执行状态改成 `FAILED`，返回调用方传入的状态。评估通过时也不改状态。

终态只有 `SUCCEEDED`、`FAILED`、`CANCELLED`、`SUSPENDED`。进入终态之后再跑一次 `completion.nonempty`。这次自动评估的 `required` 不是 true，所以空收尾只记下不通过，不把原来的 `SUCCEEDED` 改成 `FAILED`。执行状态还不是终态时，终态评估以错误码 `INTERNAL` 失败，不返回 null，也不调用模型。

## 数据库

这次不建表，也不写 JDBC。评估结果留在返回值里，落库留给持久化需求。

## 中间件

这次不启动 HTTP 服务，也不连接消息中间件。测试使用假的 `ChatModel`，不访问外网。

## 测试要求

1. `completion.nonempty` 在收尾文本去掉首尾空白后仍有字符时通过，得分为 1，并且不调用模型。
2. `completion.nonempty` 在收尾为 null、空串或只有空白时不通过，得分为 0。
3. `tool.success.rate` 在没有工具调用时得分为 1，评估通过。
4. `tool.success.rate` 在全部工具调用成功时得分为 1，评估通过，并且不调用模型。
5. `tool.success.rate` 在存在失败的工具调用时不通过，得分等于成功次数除以总次数。
6. `jasagent.evaluation.judge-enabled` 的值不是 `true` 时，`llm.judge` 不调用 `ChatModel`，评估不通过，得分为 0，原因是 `judge-disabled`。
7. `jasagent.evaluation.judge-enabled` 为 `true` 时，`llm.judge` 调用已有 `ChatModel.complete`，任务为 `JUDGE`，不点名供应方，工具列表为空，执行 id 原样传入，用户消息包含收尾文本和工具轨迹。
8. `llm.judge` 把模型返回的 JSON 对象解析为 `passed`、`score` 和 `reason`，字段顺序不影响解析。
9. `llm.judge` 在模型正文无法按约定解析时不通过，得分为 0，原因是 `judge-unparseable`，返回的评估结果不是 null。
10. `required` 不是 true 时，评估不通过不把执行状态改成 `FAILED`。
11. `required` 为 true 时，评估不通过把执行状态改成 `FAILED`。
12. 评估通过时保持原来的执行状态。
13. 执行进入 `SUCCEEDED`、`FAILED`、`CANCELLED` 或 `SUSPENDED` 之后运行 `completion.nonempty`，非空收尾通过。
14. 终态后的 `completion.nonempty` 遇到空收尾时不通过，并且不把 `SUCCEEDED` 改成 `FAILED`。
15. 执行还不是终态时，终态评估以错误码 `INTERNAL` 失败，不返回 null，也不调用模型。
16. 未知评估器 id 不通过，得分为 0，原因是 `unknown-evaluator`，并且不调用模型。
17. `ChatModel` 调用失败时，评估的 `CompletionStage` 带上原来的异常，不返回 null。
18. `jasagent-evaluation` 的构建描述不依赖 Spring，也不依赖 Spring AI。
19. 只有配置值恰好是 `true` 时才启用 `llm.judge`，其他值视为关闭。

## 还没定

无。空轨迹得分、通过线、评判 JSON 和终态自动评估的 `required` 按上一节实现。
