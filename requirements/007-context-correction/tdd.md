# 007 上下文与纠正 note 的测试用例

## 1. defaultBudgetIs8000

要求：`ContextWindow.DEFAULT_BUDGET_TOKENS` 是 8000。

看到的结果：常量等于 8000。

## 2. noteSitsBeforeCurrentUser

要求：预算为 8000 且上下文没有超出时，不调用模型，最后一条是当前用户消息，它前面的两条依次是传入的两条纠正 note。

看到的结果：模型调用次数是 0。最后一条角色是 `user`，正文是 `CURRENT`。前一条角色是 `correction`，正文是 `NOTE-B`。再前一条角色是 `correction`，正文是 `NOTE`。

## 3. unchangedWhenUnderBudget

要求：预算为 8000 且上下文没有超出时，旧工具结果和技能正文都保持原文，并且没有摘要消息。

看到的结果：拼在一起的正文含有 `SHORT-PADDING` 和 `SKILL-BODY-VERY-LONG`，没有任何角色为 `summary` 的消息。

## 4. noteRemainsAfterCompression

要求：超过预算并完成压缩后，最后一条仍是当前用户消息，它前面的两条仍是那两条纠正 note。

看到的结果：预算 30 时，最后一条角色是 `user`，正文是 `CURRENT`。前一条正文是 `NOTE-B`，再前一条正文是 `NOTE`，两条角色都是 `correction`。

## 5. protectedPartsStay

要求：压缩后的上下文仍含有规则、技能名称、技能描述、未完成任务板、最近 4 条轮次和 `executionId`。

看到的结果：预算 30 的结果里同时有 `RULE`、`research`、`查找资料`、`BOARD`、`RECENT-1`、`RECENT-4` 和 `exec-ctx`。角色为 `system` 的消息只有一条，并且含有 `RULE` 和 `exec-ctx`。

## 6. overBudgetUsesCompressRoute

要求：超过预算且存在可摘要的旧轮次时，模型只被调用一次，任务是 `compress`，点名的供应方是 null，`executionId` 等于这次执行，准备结果里的摘要等于模型返回的正文。

看到的结果：请求只有 1 次，任务是 `COMPRESS`，`wireName` 是 `compress`，`namedProvider` 是 null，`executionId` 是 `exec-ctx`。`summary()` 是 `SHORT`。有一条角色为 `summary` 的消息，正文是 `SHORT`。

## 7. summaryRequestOmitsProtectedText

要求：压缩请求的消息里没有规则、技能名称、技能描述、技能正文、纠正 note、未完成任务板、`executionId`、最近 4 条轮次和旧工具结果，并且含有被摘要的旧用户轮次。

看到的结果：请求里只有一条角色为 `user` 的消息，正文以 `OLD-` 开头。拼在一起的请求正文不含 `RULE`、`research`、`查找资料`、`SKILL-BODY-VERY-LONG`、`NOTE`、`BOARD`、`exec-ctx`、`RECENT-1`、`RECENT-4` 和 `SHORT-PADDING`。

## 8. noSummaryCallWhenNothingOld

要求：去掉最近 4 条之后没有非工具消息时，不调用模型；上下文仍超预算时，旧工具结果收成一行，技能正文在收成一行后仍超预算时被去掉。

看到的结果：只有 4 条短轮次和一条很长的技能正文、预算为 30 时，模型调用次数是 0，结果不含 `SKILL-BODY-VERY-LONG`，仍含有 `research`、`查找资料` 和 `RECENT-1`。只有一条很长的旧工具结果和 4 条短轮次、预算为 40 时，模型调用次数是 0，结果含有 `tool: [collapsed]`，不含 `OLD-TOOL-BODY`。

## 9. oldToolResultsCollapse

要求：摘要之后仍然超过预算时，最近 4 条之外的每条工具结果都变成 `tool: [collapsed]`，原来的工具正文不再出现。

看到的结果：两条旧工具结果的正文都是 `tool: [collapsed]`，拼在一起的结果不含 `OLD-TOOL-BODY`，仍含有 `RECENT-1`。

## 10. recentToolResultsStay

要求：最近 4 条之内的工具结果在收成一行时保持原文。

看到的结果：结果含有 `RECENT-TOOL-BODY` 和 `tool: [collapsed]`，不含 `OLD-TOOL-BODY`。

## 11. skillsShrinkToNameAndDescription

要求：收成一行之后仍然超过预算时，提示里有技能名称和描述，没有技能正文。

看到的结果：结果含有 `research` 和 `查找资料`，不含 `SKILL-BODY-VERY-LONG`。

## 12. skillsStayWhenCollapseFits

要求：收成一行之后没有超过预算时，技能正文仍在，旧工具结果已经变成 `tool: [collapsed]`。

看到的结果：结果含有 `BODY` 和 `tool: [collapsed]`，不含连续 50 个 `X`。

## 13. forbiddenPartsStayWhenStillOverBudget

要求：摘要、收成一行和降技能之后，预算仍然不够时，纠正 note、未完成任务板和 `executionId` 仍在。

看到的结果：预算 20 且摘要很长时，仍有正文为 `NOTE` 和 `NOTE-B` 的纠正消息，system 含有 `BOARD` 和 `exec-ctx`。

## 14. noteStoredInUserDirAndSnapshot

要求：追加一条纠正 note 后，用户目录里的 JSON 含有 `noteId`、`sessionId`、`executionId`、`createdAt`、`source` 和 `text`，执行快照里是同一条 note。

看到的结果：`corrections/sess-1/n1.json` 含有这些字段和转义后的引号。快照列表的第一条等于传入的 note，正文仍是 `NOTE "keep"`。

## 15. fourSourcesRoundTrip

要求：`USER`、`TOOL_CONTRADICTION`、`GROUNDING`、`EVALUATION` 都能写入用户目录，枚举也只有这四个值。

看到的结果：`CorrectionSource.values()` 按这个顺序正好是这四个值。四个文件分别含有对应的枚举名。

## 16. invalidNoteIsRejected

要求：note 为 null、字段为空白、`source` 或 `createdAt` 为 null，或者 `noteId`、`sessionId` 含有路径片段时，追加失败，错误码是 `INTERNAL`，不写文件，快照消费者不被调用。

看到的结果：每种无效输入都抛出 `AgentException`，错误码是 `INTERNAL`。快照列表是空的，用户目录下没有 json 文件。

## 17. toolContradictionKeepsToolResult

要求：助手消息里的英文双引号或中文弯引号片段和工具结果冲突时，追加一条 `TOOL_CONTRADICTION`，返回列表末尾是工具结果全文，原来的历史列表长度不变。

看到的结果：两种引号各追加一条 note，`source` 是 `TOOL_CONTRADICTION`，`sessionId` 是 `sess-1`，`executionId` 是 `exec-ctx`，正文以 `以工具结果为准：` 开头并含有 `12:00 UTC`。返回列表最后一条角色是 `tool`，正文是 `12:00 UTC`。传入的历史列表长度仍是 1。

## 18. quotedFragmentInEvidenceAddsNoNote

要求：引号片段能在工具结果或用户消息里找到、助手消息没有引号，或者工具结果为空白时，不追加纠正 note。

看到的结果：四种情况的 note 列表都是空的。工具结果为空白时，返回列表的长度等于原来的历史长度。工具结果非空且没有冲突时，最后一条是该工具结果全文。

## 19. rejectsBlankExecutionAndBadBudget

要求：`budgetTokens` 小于 1，或 `executionId` 为空白时，`prepare` 失败，错误码是 `INTERNAL`，不调用模型。

看到的结果：预算 0 和空白 `executionId` 都抛出 `AgentException`，错误码是 `INTERNAL`，模型调用次数是 0。

## 20. nullModelRejected

要求：没有 `ChatModel` 时，构造 `ContextWindow` 失败，错误码是 `INTERNAL`。

看到的结果：构造时抛出 `AgentException`，错误码是 `INTERNAL`。
