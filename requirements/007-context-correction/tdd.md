# 007 上下文与纠正 note 的测试用例

## 1. noteSitsBeforeCurrentUser

要求：纠正 note 位于当前用户消息的前一条。

看到的结果：倒数第二条包含 note 正文，最后一条是当前用户消息。

## 2. noteRemainsAfterCompression

要求：压缩之后，纠正 note 仍是当前用户消息的前一条。

看到的结果：压缩后的消息里，倒数第二条仍包含 note 正文，最后一条仍是当前用户消息。

## 3. protectedPartsStay

要求：压缩不会去掉规则、未完成任务板和最近 4 轮。

看到的结果：压缩后的文本同时包含规则、任务板和最近 4 轮里的标记。

## 4. overBudgetUsesCompressRoute

要求：超过预算时，模型任务是 `compress`。

看到的结果：模型收到的任务等于 `COMPRESS`。

## 5. oldToolResultsCollapse

要求：摘要之后仍然超过预算时，旧工具结果变成一行。

看到的结果：旧工具消息的正文以 `tool:` 开头，并且不再包含原来的长正文。

## 6. skillsShrinkToName

要求：收成一行后仍然超过预算时，技能正文只剩名称和描述。

看到的结果：提示里有技能名称和描述，没有技能长正文。

## 7. executionIdSurvivesCompression

要求：压缩后的 system 消息仍包含 `executionId`。

看到的结果：system 消息包含这次执行编号。

## 8. noteStoredInUserDirAndSnapshot

要求：追加纠正 note 后，用户目录和执行快照里都能读到同一条正文。

看到的结果：用户目录里的 json 和快照列表都包含这条正文。

## 9. toolContradictionKeepsToolResult

要求：工具结果与助手引号片段冲突时，追加 `TOOL_CONTRADICTION`，并且后续消息保留工具结果全文。

看到的结果：note 来源是 `TOOL_CONTRADICTION`，后续消息里能找到工具结果全文。
