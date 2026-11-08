# 005 工具、插件和通行证

## 这次要改什么

现状是工作流已经能通过端口调用工具和插件，但还没有工具目录、内置时钟、通行证和进程内插件。这次补上这些端口的实现。不改权限策略、审批、管理页面和模型清单。`jasagent-tool` 与 `jasagent-plugin` 只调用 `jasagent-api` 里的 `PassGate`，不依赖 `jasagent-agentpass` 的类。

## 模块

这次新增 `jasagent-tool`、`jasagent-plugin`、`jasagent-agentpass`，并在 `jasagent-api` 增加通行证、工具和插件端口。三个实现模块的 Maven 依赖只指向 `jasagent-api`。

## 改动地方

工具目录遇到同名工具时启动失败，错误码 `TOOL`。`clock` 返回 UTC 时间，它是内部工具，调用前不申请通行证。外部工具和插件在执行前调用 `PassGate.acquire`。`PassRequest` 带 `userId`、`executionId`、工具名或插件标识、参数哈希。决定只允许 `ALLOW`、`DENY`、`SKIPPED`。`DENY` 时不执行工具或插件本体。`ALLOW` 和 `SKIPPED` 才执行。每次决定写入调用记录。

`DisabledPassGate` 固定返回 `SKIPPED`，原因是 `agentpass-not-enabled`。本版不写权限策略。

插件用 `ServiceLoader` 装载。附带 `grounding.default`，只在 `afterModel` 调用 `HallucinationGuard`。插件失败默认记录后继续；调用方要求失败时，节点以错误码 `PLUGIN` 失败。

## 数据库

这次不改表。通行证决定先写入调用记录端口，由组装点以后落到 `tool_call` 或 `plugin_call`。

## 中间件

这次不访问外网，也不启动 HTTP。

## 测试要求

1. 同名工具放入目录时失败，错误码是 `TOOL`。
2. `clock` 返回注入时钟的 UTC 时间，并且通行证没有被申请。
3. 外部工具执行前会申请通行证。
4. 通行证为 `DENY` 时，工具本体不会执行。
5. 通行证为 `SKIPPED` 时，工具本体会执行。
6. 通行证为 `ALLOW` 时，工具本体会执行。
7. `DisabledPassGate` 返回 `SKIPPED`，原因是 `agentpass-not-enabled`。
8. 每次通行证决定都会进入调用记录。
9. 插件执行前会申请通行证。
10. 插件抛错且未要求失败时，调用仍然成功结束。
11. 插件抛错且要求失败时，错误码是 `PLUGIN`。
12. `grounding.default` 在 `afterModel` 调用已注册的 `HallucinationGuard`。
13. `ServiceLoader` 能装载到标识为 `grounding.default` 的插件。

## 还没定

无。
