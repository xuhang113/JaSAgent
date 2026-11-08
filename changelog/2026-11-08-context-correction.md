# 2026-11-08 上下文与纠正 note

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：超过预算时走 compress 路由压缩旧轮次，保留规则、任务板、最近 4 轮、纠正 note 和 executionId。纠正 note 写入用户目录和执行快照。工具结果与助手记忆冲突时追加 TOOL_CONTRADICTION。
- 涉及模块：jasagent-core
- 怎么验证：在仓库根目录执行 `mvn test`。`ContextCorrectionTest` 9 项与此前全部用例通过，失败 0，错误 0，跳过 0。验收脚本 `requirements/007-context-correction/tests/007-context-correction.sh` 退出码为 0。
