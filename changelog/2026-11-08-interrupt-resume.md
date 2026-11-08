# 2026-11-08 中止与恢复

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：中止取消当前节点并把状态写成 SUSPENDED，检查点保持不变。resume 使用原来的 executionId。非 SUSPENDED 直接返回快照。关闭 H2 后再打开仍能恢复，traceId 不变。
- 涉及模块：jasagent-api、jasagent-persist、jasagent-interrupt、jasagent-app
- 怎么验证：在仓库根目录执行 `mvn test`。`InterruptResumeTest` 5 项、`ResumeAfterRestartTest` 1 项与此前全部用例通过，失败 0，错误 0，跳过 0。验收脚本 `requirements/008-interrupt-resume/tests/008-interrupt-resume.sh` 退出码为 0。
