# 2026-11-08 工具、插件和通行证

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：工具目录可调用内置 clock 和外部工具。clock 返回 UTC 时间且不申请通行证。外部工具和插件执行前调用 api 的 PassGate。本用户 `auth/policies.yaml` 命中则 ALLOW 或 DENY，未命中为 PENDING 且不执行，执行状态记为 SUSPENDED。显式关闭通行证时为 SKIPPED 并执行。默认开启。DENY 不执行。同一用户批准后用原 executionId 继续执行。插件经 ServiceLoader 装载，含 grounding.default。这三个模块不使用 Spring AI，也不手写图执行。
- 涉及模块：jasagent-api、jasagent-tool、jasagent-plugin、jasagent-agentpass
- 怎么验证：在仓库根目录执行 `mvn test`，`LoginDirectoryTest` 18 项、`ToolCatalogTest` 13 项、`PluginHostTest` 14 项、`PassGateTest` 15 项通过，失败 0，错误 0，跳过 0。验收脚本 `requirements/005-tool-plugin-agentpass/tests/005-tool-plugin-agentpass.sh` 退出码为 0。
