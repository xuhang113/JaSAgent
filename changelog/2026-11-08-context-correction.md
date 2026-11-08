# 2026-11-08 上下文与纠正 note

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：超过预算时用 `compress` 摘要旧轮次。规则、技能、全部纠正 note、未完成任务板和最近 4 条轮次不进摘要。摘要后仍超预算时，先把旧工具结果收成一行，再把技能正文降成名称和描述。纠正 note 紧挨当前用户消息，并写入用户目录和执行快照。工具结果与助手引号片段冲突时追加 `TOOL_CONTRADICTION`。
- 涉及模块：`jasagent-core`
- 怎么验证：仓库根 `mvn test` 通过。`ContextCorrectionTest` 20 项与此前用例一起通过，合计 270，失败 0，错误 0，跳过 0。`requirements/007-context-correction/tests/007-context-correction.sh` 在实现前因找不到上下文类而失败，实现后退出码为 0。
