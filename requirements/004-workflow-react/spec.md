# 004 工作流与 ReAct

## 这次要改什么

现状是登录、模型路由、落库和追踪已经可用，还没有工作流步骤机。这次只增加 DSL 装载和节点执行。节点类型只允许 `react`、`tool`、`plugin`、`subagents`、`evaluate`、`interrupt`、`condition`、`retrieve`、`end`。不改工具通行证、主队列调度、检索融合和页面。模型、工具、检索、评估、子 agent 和插件都通过端口注入，本模块不依赖它们的实现。

## 模块

这次扩展 `jasagent-api` 的工作流端口，并新增 `jasagent-workflow`。该模块的 Maven 依赖只指向 `jasagent-api` 和 JSON 库。

## 改动地方

工作流用 JSON 描述。`id`、`rules`、`skills`、`budgetTokens` 和 `nodes` 会被装载。示例 `answer-with-tool` 含节点 `find`（retrieve）、`act`（react）、`grade`（evaluate）、`done`（end）。非法节点类型拒绝装载，错误码 `WORKFLOW`。

`react` 发给模型的消息顺序固定为：system（角色、规则、技能、未完成任务板和 executionId）、压缩摘要、最近轮次、纠正 note、当前用户消息。纠正 note 紧挨在当前用户消息之前。模型返回工具调用时执行工具，返回正文时结束这一节点。`maxSteps` 用尽仍未结束则失败，错误码 `WORKFLOW_MAX_STEPS`。同一步里的多个工具调用并行启动，全部完成后再进入下一步。

`grounding` 为真时，最终回答里的数字和引号片段必须能在本次工具结果、检索命中或用户消息里找到。找不到就追加一条来源为 `GROUNDING` 的纠正 note，并且只再请求一次模型。

`condition` 只比较上一节点输出：布尔、字符串相等、数值阈值。`retrieve` 的 `required` 不是 true 时，空结果不让工作流失败。进入下一节点前必须先提交检查点；提交失败则不进入该节点，错误码 `PERSIST`。

节点上的插件按阶段调用：`onNodeStart`、`beforeModel`、`afterModel`、`beforeTool`、`afterTool`、`onNodeEnd`。`subagents` 没有 `childWorkflow` 时，交给子 agent 的流程只有一个 `react` 节点。

## 数据库

这次不改表。检查点通过端口提交，端口的 JDBC 实现留给后续需求。

## 中间件

这次不启动 HTTP，也不访问外网。测试使用脚本化的模型客户端。

## 测试要求

1. 节点类型不在允许集合内时，装载失败，错误码是 `WORKFLOW`。
2. 示例工作流 `answer-with-tool` 能装载，节点标识依次包含 `find`、`act`、`grade`、`done`。
3. `react` 的消息顺序是 system、压缩摘要、最近轮次、纠正 note、当前用户消息，且纠正 note 紧挨当前用户消息。
4. `react` 能完成一次工具调用后再结束，工具只被调用一次。
5. `maxSteps` 用尽时失败，错误码是 `WORKFLOW_MAX_STEPS`。
6. grounding 找不到依据时追加来源为 `GROUNDING` 的纠正 note。
7. grounding 只再请求一次模型。
8. 同一步的多个工具调用会并行启动，汇合后才进入下一步。
9. `condition` 能按字符串相等选择分支。
10. `condition` 能按数值阈值选择分支。
11. `condition` 能按布尔值选择分支。
12. `required` 不是 true 的 retrieve 得到空结果时，工作流状态仍是 `SUCCEEDED`。
13. 检查点提交失败时，失败节点的工具不会执行，错误码是 `PERSIST`。
14. 有工具调用时，`afterModel` 出现在 `beforeTool` 之前，`beforeTool` 出现在 `afterTool` 之前，并且 `onNodeStart` 和 `onNodeEnd` 都会出现。
15. `subagents` 没有 `childWorkflow` 时，子流程文档里只有一个类型为 `react` 的节点。

## 还没定

无。
