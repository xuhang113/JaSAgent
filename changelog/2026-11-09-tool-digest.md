# 2026-11-09 工具结果压缩改成短摘要

- 日期：2026-11-09（Asia/Shanghai）
- 做了什么：上下文放不进预算时，不再把旧工具结果换成 `tool: [collapsed]`。改成短摘要：目录列举是 `list_directory：列出目录。结果：文件总数 N`，其它结果是 `工具：保留要点。结果：` 加去掉重复字符后的要点。没有可读内容就删掉这条。
- 涉及模块：jasagent-core
- 怎么验证：`JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -pl jasagent-core -am test -Dtest=ContextCorrectionTest,ContextCompressGateTest`，27 条通过。
