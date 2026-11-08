# 2026-11-08 外部插件工具

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：用 ServiceLoader 登记外部插件工具 `web.search`、`rag.link`、`github.link`。调用前仍走 PassGate。成功和失败次数记入 `jasagent-api` 的 `CallStats`，不另建统计口径。`clock` 仍不申请通行证，也不自动计入这组次数。
- 涉及模块：jasagent-api、jasagent-plugin、jasagent-tool
- 怎么验证：本机 JDK 21。验收脚本 `requirements/014-plugin-tools/tests/014-plugin-tools.sh` 退出码为 0。仓库根 `mvn test` 通过，失败 0，错误 0，跳过 0。
