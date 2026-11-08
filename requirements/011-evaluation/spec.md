# 011 评估

## 这次要改什么

现状是工作流可以跑到终态，但还没有内置评估器。这次补上 `completion.nonempty`、`tool.success.rate`，以及可选的 `llm.judge`。不改评估管理页面。

## 模块

这次新增 `jasagent-evaluation`。它只依赖 `jasagent-api`。

## 改动地方

`completion.nonempty` 在输出去掉空白后仍有内容时通过，否则不通过。`tool.success.rate` 在没有工具调用或全部成功时通过，有失败时不通过。`jasagent.evaluation.judge-enabled` 不是 true 时不调用 judge 路由。为 true 时，judge 请求的任务是 `judge`。终态之后再跑一次 `completion.nonempty`。`required` 不是 true 时，评估不通过不把状态改成 `FAILED`。`required` 为 true 且评估不通过时，状态改为 `FAILED`。

## 数据库

这次不改表。评估结果以后写入已有的 `evaluation` 表。

## 中间件

这次不访问外网。judge 测试使用脚本化模型。

## 测试要求

1. 非空输出让 `completion.nonempty` 通过，空白输出不通过。
2. 全部工具成功时 `tool.success.rate` 通过，存在失败时不通过。
3. judge 未启用时不调用模型。
4. judge 启用时，模型任务是 `judge`。
5. 终态之后会再跑 `completion.nonempty`，空输出记为不通过。
6. `required` 不是 true 时，评估不通过仍保持原成功状态。
7. `required` 为 true 时，评估不通过把状态改成 `FAILED`。

## 还没定

无。
