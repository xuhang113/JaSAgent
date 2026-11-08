# 2026-11-08 工作流与 ReAct

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：装载 JSON 工作流，执行 react、tool、retrieve、condition、evaluate、subagents 和 end。react 按固定消息顺序调用模型，同一步工具并行，grounding 只重试一次，检查点失败不进入节点。
- 涉及模块：jasagent-api、jasagent-workflow
- 怎么验证：在仓库根目录执行 `mvn test`。`WorkflowReactTest` 15 项与此前全部用例通过，失败 0，错误 0，跳过 0。验收脚本 `requirements/004-workflow-react/tests/004-workflow-react.sh` 退出码为 0。
