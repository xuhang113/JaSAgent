# 2026-11-08 轨迹和输出评估

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：新增内置评估器 `completion.nonempty` 和 `tool.success.rate`。`llm.judge` 只有 `jasagent.evaluation.judge-enabled=true` 时才调用已有 `ChatModel`，任务为 `JUDGE`，不点名供应方。`required` 不是 true 时，评估不通过不把执行状态改成 `FAILED`。终态后再跑 `completion.nonempty`。
- 涉及模块：jasagent-evaluation
- 怎么验证：在仓库根目录执行 `mvn test`。`LoginDirectoryTest` 18 项、`LlmRouterTest` 15 项、`DeepSeekWireTest` 7 项、`EvaluationTest` 19 项全部通过，失败 0，错误 0，跳过 0。验收脚本 `requirements/011-evaluation/tests/011-evaluation.sh` 退出码为 0。实现之前该脚本因生产代码不存在而失败。
