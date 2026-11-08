# 2026-11-08 中止与原 id 恢复

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：执行中的中止取消当前节点的 CompletionStage，停在最后一次成功检查点，状态改为 SUSPENDED。resume 使用原来的 executionId 从该检查点继续。状态不是 SUSPENDED 时，resume 返回当前快照。关掉控制对象后，用同一个 langgraph4j saver 文件仍能恢复，恢复后的 span 继续使用保存的 traceId。检查点默认使用 SQLiteSaverV2，JDBC URL 以 jdbc:postgresql: 开头时使用 PostgresSaverV2。
- 涉及模块：jasagent-interrupt
- 怎么验证：在仓库根目录执行 `mvn test`。21 个模块全部 SUCCESS。测试合计 231，失败 0，错误 0，跳过 0。其中 `InterruptResumeTest` 17 项。验收脚本 `requirements/008-interrupt-resume/tests/008-interrupt-resume.sh` 退出码为 0。
