# 005 工具、插件和通行证的测试用例

## 1. duplicateToolRejected

要求：同名工具放入目录时失败，错误码是 `TOOL`。

看到的结果：用两个同名工具构造目录时抛出 `TOOL`。

## 2. clockReturnsUtcWithoutPass

要求：`clock` 返回注入时钟的 UTC 时间，并且通行证没有被申请。

看到的结果：工具正文等于该时刻的 `Instant` 文本，通行证申请次数是 0。

## 3. externalToolAcquiresPass

要求：外部工具执行前会申请通行证。

看到的结果：通行证申请次数是 1，请求里的工具名等于该外部工具名。

## 4. denySkipsToolBody

要求：通行证为 `DENY` 时，工具本体不会执行。

看到的结果：工具计数器仍是 0，调用记录里的决定是 `DENY`。

## 5. skippedRunsTool

要求：通行证为 `SKIPPED` 时，工具本体会执行。

看到的结果：工具计数器是 1。

## 6. allowRunsTool

要求：通行证为 `ALLOW` 时，工具本体会执行。

看到的结果：工具计数器是 1。

## 7. disabledPassGateSkips

要求：`DisabledPassGate` 返回 `SKIPPED`，原因是 `agentpass-not-enabled`。

看到的结果：决定是 `SKIPPED`，原因等于 `agentpass-not-enabled`。

## 8. passDecisionIsRecorded

要求：每次通行证决定都会进入调用记录。

看到的结果：记录列表有一条，决定与网关返回值一致。

## 9. pluginAcquiresPass

要求：插件执行前会申请通行证。

看到的结果：通行证申请次数是 1，请求里的插件标识等于该插件标识。

## 10. pluginFailureContinues

要求：插件抛错且未要求失败时，调用仍然成功结束。

看到的结果：等待调用得到的结果是成功，没有抛出错误。

## 11. pluginFailureFailsNode

要求：插件抛错且要求失败时，错误码是 `PLUGIN`。

看到的结果：等待调用抛出 `PLUGIN`。

## 12. groundingPluginCallsGuard

要求：`grounding.default` 在 `afterModel` 调用已注册的 `HallucinationGuard`。

看到的结果：守卫被调用一次，并且收到的回答文本等于这次上下文里的回答。

## 13. serviceLoaderFindsGroundingPlugin

要求：`ServiceLoader` 能装载到标识为 `grounding.default` 的插件。

看到的结果：装载出的插件标识里包含 `grounding.default`。
