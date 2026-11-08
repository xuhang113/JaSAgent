# 2026-11-08 工具、插件和通行证

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：增加工具目录和 UTC 时钟，外部工具与插件执行前申请通行证。默认通行证返回 SKIPPED，原因是 agentpass-not-enabled。DENY 不执行本体。grounding.default 在 afterModel 调用幻觉守卫。
- 涉及模块：jasagent-api、jasagent-tool、jasagent-plugin、jasagent-agentpass
- 怎么验证：在仓库根目录执行 `mvn test`。`ToolPassTest` 7 项、`PluginPassTest` 5 项、`DisabledPassGateTest` 1 项与此前全部用例通过，失败 0，错误 0，跳过 0。验收脚本 `requirements/005-tool-plugin-agentpass/tests/005-tool-plugin-agentpass.sh` 退出码为 0。
