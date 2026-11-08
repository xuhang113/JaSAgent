# 005 工具、插件和通行证的测试用例

## 1. duplicateToolNameFailsStartup

要求：同名工具在目录构造时失败，错误码为 `TOOL`。

看到的结果：用两个名为 `clock` 的工具构造目录时抛出 `AgentException`，错误码为 `TOOL`。

## 2. clockReturnsUtcWithoutPass

要求：`clock` 的输出能被解析成 UTC 瞬时，并且这次调用没有进入 `acquire`。

看到的结果：输出能被 `Instant.parse` 解析，与当前时间相差不超过五秒，结果里没有通行证，`acquire` 的调用次数为 0。

## 3. externalToolAcquiresPassBeforeBody

要求：外部工具在工具体执行前调用 `acquire`，请求中的 `userId`、`executionId`、工具名、用户目录和工作区目录与这次调用一致，此时工具体还没有执行。

看到的结果：`acquire` 收到的这五项与调用一致，进入 `acquire` 时工具体执行次数仍为 0，调用结束后工具体执行次数为 1。

## 4. argumentHashMatchesIndependentSha256

要求：外部工具的参数哈希等于测试用独立 `MessageDigest` 对排序后的 `key=value` 行计算的 SHA-256 小写十六进制。

看到的结果：参数 `b=2` 与 `a=1` 的哈希等于测试自己对 `a=1\nb=2` 计算的 SHA-256。

## 5. denyDoesNotExecuteTool

要求：通行证为 `DENY` 时工具体不执行，结果中的决定是 `DENY`。

看到的结果：工具体执行次数为 0，结果的 `executed` 为假，决定为 `DENY`。

## 6. allowExecutesTool

要求：通行证为 `ALLOW` 时工具体执行，结果带回工具体的输出。

看到的结果：工具体执行次数为 1，结果输出等于工具体返回的文本，决定为 `ALLOW`。

## 7. pendingDoesNotExecuteTool

要求：通行证为 `PENDING` 时工具体不执行。

看到的结果：工具体执行次数为 0，结果的 `executed` 为假，决定为 `PENDING`。

## 8. skippedExecutesTool

要求：通行证为 `SKIPPED` 时工具体执行。

看到的结果：工具体执行次数为 1，决定为 `SKIPPED`，reason 为 `agentpass-not-enabled`。

## 9. allowPendingExecutesOriginalExecution

要求：挂起的工具被同一用户批准为 `ALLOW` 后，工具体执行，结果中的 `executionId` 仍是原来的那一个。

看到的结果：批准前工具体执行次数为 0，批准后为 1，结果和工具体看到的 `executionId` 都是原来的 `exec-tool`。

## 10. denyPendingDoesNotExecute

要求：挂起的工具被同一用户拒绝为 `DENY` 后，工具体不执行。

看到的结果：拒绝后工具体执行次数仍为 0，结果的决定为 `DENY`。

## 11. decisionsAreWrittenToToolCall

要求：`DENY` 和 `PENDING` 都会写入该 `executionId` 的 `tool_call`，并且 `executed` 为假。

看到的结果：该 `executionId` 下能找到 `DENY` 和 `PENDING` 两条记录，两条的 `executed` 都为假。

## 12. externalCallsCanOverlap

要求：两个外部工具调用可以同时停在 `acquire` 上，目录不会把第二次调用排到第一次完全结束之后。

看到的结果：两次调用都还没被放行时，`acquire` 的进入次数已经是 2。

## 13. enabledDefaultsTrue

要求：配置里没有 `jasagent.agentpass.enabled` 时视为开启，值为 `true` 时视为开启，值为 `false` 时视为关闭。

看到的结果：空配置和 `true` 得到开启，`false` 得到关闭。

## 14. disabledPassIsSkipped

要求：通行证关闭时，`acquire` 返回 `SKIPPED`，reason 为 `agentpass-not-enabled`，执行状态不是 `SUSPENDED`。

看到的结果：决定为 `SKIPPED`，reason 为 `agentpass-not-enabled`，该执行没有被标成 `SUSPENDED`。

## 15. matchedToolAllow

要求：用户策略把工具名匹配为 `ALLOW` 时，`acquire` 返回 `ALLOW`。

看到的结果：工具 `weather` 的决定为 `ALLOW`，reason 为 `policy`。

## 16. matchedToolDenyDoesNotSuspend

要求：用户策略把工具名匹配为 `DENY` 时，`acquire` 返回 `DENY`，执行状态不是 `SUSPENDED`。

看到的结果：决定为 `DENY`，该执行没有被标成 `SUSPENDED`。

## 17. matchedPluginDeny

要求：用户策略把插件 id 匹配为 `DENY` 时，`acquire` 返回 `DENY`。

看到的结果：插件 `grounding.default` 的决定为 `DENY`。

## 18. firstRuleWins

要求：同一工具名有多条规则时，采用文件中靠前的那一条。

看到的结果：靠前的 `DENY` 生效，后面的 `ALLOW` 不生效。

## 19. unmatchedIsPending

要求：没有命中规则时，`acquire` 返回 `PENDING`，reason 为 `approval-required`。

看到的结果：决定为 `PENDING`，reason 为 `approval-required`。

## 20. unmatchedSuspendsExecution

要求：没有命中规则时，该 `executionId` 的执行状态变为 `SUSPENDED`。

看到的结果：`executionStatus` 返回 `SUSPENDED`。

## 21. unmatchedIsRecordedForThatUser

要求：没有命中规则时，该用户能从自己的目录读回这条待审批，另一用户读不到；记录文件权限仅所有者可读写；新的 `PassGate` 实例从该目录仍能读到同一 `passId`。

看到的结果：原实例和新实例都能为该用户读到同一 `passId`，另一用户的列表为空，文件权限恰好是所有者读和所有者写。

## 22. otherUserCannotApprove

要求：另一用户提交 `ALLOW` 时失败，错误码为 `AUTH`，待审批仍是 `PENDING`。

看到的结果：审批抛出 `AUTH`，该用户的待审批决定仍是 `PENDING`。

## 23. approvalExecutionIdMustMatch

要求：审批请求中的 `executionId` 与记录不一致时失败，错误码为 `AUTH`，待审批仍是 `PENDING`。

看到的结果：审批抛出 `AUTH`，待审批决定仍是 `PENDING`。

## 24. workspacePolicyCannotRelax

要求：工作区目录中的 `ALLOW` 不能覆盖用户目录中的 `DENY`，也不能把用户目录里没有命中的工具变成 `ALLOW`。

看到的结果：用户写了 `DENY` 的工具仍是 `DENY`；用户没有写的工具仍是 `PENDING`。

## 25. policiesStayWithEachUser

要求：两个用户目录中的策略互不影响。

看到的结果：同一工具名在甲的目录是 `ALLOW`，在乙的目录是 `DENY`。

## 26. malformedPolicyRejected

要求：策略文件存在但无法按约定解析时，`acquire` 以错误码 `INTERNAL` 失败。

看到的结果：`acquire` 抛出 `INTERNAL`。

## 27. decidedPassCannotBeApprovedAgain

要求：已经不是 `PENDING` 的通行证再次审批时，以错误码 `INTERNAL` 失败，原决定不变。

看到的结果：对一条 `ALLOW` 再次审批抛出 `INTERNAL`，原决定仍是 `ALLOW`。

## 28. serviceLoaderLoadsGroundingDefault

要求：`ServiceLoader` 的装载结果包含 `grounding.default`。

看到的结果：宿主的 id 列表包含 `grounding.default`。

## 29. groundingDefaultRunsOnAfterModel

要求：`grounding.default` 在 `afterModel` 调用 `HallucinationGuard`，并传入这次的 `executionId` 和模型输出。

看到的结果：守卫收到的 `executionId` 和模型输出与这次调用一致，次数为 1。

## 30. groundingDefaultSkipsGuardOnOtherPhase

要求：`grounding.default` 在 `beforeModel` 不调用 `HallucinationGuard`。

看到的结果：守卫调用次数为 0，插件结果的 `executed` 为真。

## 31. pluginAcquiresPassBeforeBody

要求：插件在进入插件体之前调用 `acquire`，请求中的 `userId`、`executionId` 和插件 id 与这次调用一致，此时插件体还没有执行。

看到的结果：请求中的这三项与调用一致，进入 `acquire` 时插件体执行次数为 0，调用结束后为 1。

## 32. pluginArgumentHashMatchesIndependentSha256

要求：插件的参数哈希等于测试用独立 `MessageDigest` 对阶段名、参数字符串和模型输出三段计算的 SHA-256 小写十六进制。

看到的结果：哈希等于测试自己对 `afterModel\nquery\nmodel-text` 计算的 SHA-256。

## 33. denyDoesNotEnterPlugin

要求：通行证为 `DENY` 时不进入插件体。

看到的结果：插件体执行次数为 0，结果的决定为 `DENY`。

## 34. pendingDoesNotEnterPlugin

要求：通行证为 `PENDING` 时不进入插件体。

看到的结果：插件体执行次数为 0，结果的决定为 `PENDING`。

## 35. pluginFailureContinuesByDefault

要求：插件体失败且 `failure` 不是 `FAIL` 时，调用正常返回，`failed` 为真，`executed` 为假。

看到的结果：调用不抛异常，`failed` 为真，`executed` 为假。

## 36. pluginFailureFailStopsCall

要求：`failure` 为 `FAIL` 时，插件体失败使这次调用以错误码 `PLUGIN` 失败。

看到的结果：调用抛出 `AgentException`，错误码为 `PLUGIN`。

## 37. skippedExecutesPlugin

要求：通行证为 `SKIPPED` 时插件体执行。

看到的结果：插件体执行次数为 1，决定为 `SKIPPED`。

## 38. duplicatePluginIdFailsLoad

要求：同名插件在宿主构造时失败，错误码为 `PLUGIN`。

看到的结果：用两个 id 同为 `grounding.default` 的插件构造宿主时抛出 `PLUGIN`。

## 39. allowPendingExecutesOriginalExecution

要求：挂起的插件被批准为 `ALLOW` 后才进入插件体，结果中的 `executionId` 仍是原来的那一个。

看到的结果：批准前插件体执行次数为 0，批准后为 1，结果中的 `executionId` 仍是 `exec-plugin`。

## 40. denyPendingDoesNotEnterPlugin

要求：挂起的插件被拒绝为 `DENY` 后不进入插件体。

看到的结果：拒绝后插件体执行次数仍为 0，决定为 `DENY`。

## 41. decisionsAreWrittenToPluginCall

要求：`DENY` 和 `PENDING` 都会写入该 `executionId` 的 `plugin_call`，并且 `executed` 为假。

看到的结果：该 `executionId` 下能找到 `DENY` 和 `PENDING` 两条记录，两条的 `executed` 都为假。

## 42. modulesKeepPassAndGraphBoundaries

要求：`jasagent-tool`、`jasagent-plugin`、`jasagent-agentpass` 的构建文件和 Java 源文件不依赖 Spring、Spring AI 或 langgraph；tool 与 plugin 不引用 agentpass 的实现包；agentpass 不引用 tool 与 plugin 的实现包。

看到的结果：三个模块的 pom 和 Java 源文件都不含 Spring、Spring AI、langgraph 这些依赖名；tool 与 plugin 的文件不含 `dev.jasagent.agentpass`；agentpass 的文件不含 `dev.jasagent.tool` 和 `dev.jasagent.plugin`。
