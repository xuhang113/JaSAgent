# 测试规范 013

**来源**: 同目录 `spec.md` 的测试要求

## 用例

| 用例 | 来源 | 做什么 | 则 | 脚本 | 结果文件 | 日志文件 |
| --- | --- | --- | --- | --- | --- | --- |
| T-001 | 测试要求 1 | 未登录读取 `/api/sessions` | 未登录读取 `/api/sessions` 得到 401，响应体含 `errorCode` 且不含堆栈。 | `tests/T-001` | `results/T-001.txt` | `logs/T-001.log` |
| T-002 | 测试要求 2 | 读取控制台脚本 | 控制台脚本含有 `session-main` 和「子 agent」。 | `tests/T-002` | `results/T-002.txt` | `logs/T-002.log` |
| T-003 | 测试要求 3 | 读取控制台脚本 | 控制台脚本含有「纠正 note」和 `harness note`。 | `tests/T-003` | `results/T-003.txt` | `logs/T-003.log` |
| T-004 | 测试要求 4 | 读取控制台脚本 | 控制台脚本含有 `data-default-panels":"hidden"`。 | `tests/T-004` | `results/T-004.txt` | `logs/T-004.log` |
| T-005 | 测试要求 5 | 读取控制台脚本 | 控制台脚本含有 `data-resize`。 | `tests/T-005` | `results/T-005.txt` | `logs/T-005.log` |
| T-006 | 测试要求 6 | 登录用户给自己的会话发送消息后读取该会话 | 用户为自己的会话发送消息后，读取该会话能看到 `"role":"SUB"`。 | `tests/T-006` | `results/T-006.txt` | `logs/T-006.log` |
| T-007 | 测试要求 7 | 登录用户给自己的会话追加纠正 note 后读取该会话 | 用户为自己的会话追加纠正 note 后，读取该会话能看到这段文字。 | `tests/T-007` | `results/T-007.txt` | `logs/T-007.log` |
| T-008 | 测试要求 8 | 另一用户读取这条会话 | 另一用户读取该会话得到 401。 | `tests/T-008` | `results/T-008.txt` | `logs/T-008.log` |
| T-009 | 测试要求 9 | 跑一次带规则的执行并读取时间线 | 时间线里类型为 system 的消息正文含有这次写入的规则描述。 | `tests/T-009` | `results/T-009.txt` | `logs/T-009.log` |
| T-010 | 测试要求 10 | 跑一次带技能描述的执行并读取时间线 | 同一条 system 消息正文含有这次写入的技能描述。 | `tests/T-010` | `results/T-010.txt` | `logs/T-010.log` |
| T-011 | 测试要求 11 | 发送用户原文并读取时间线 | 时间线含有类型为 human 的用户原文。 | `tests/T-011` | `results/T-011.txt` | `logs/T-011.log` |
| T-012 | 测试要求 12 | 等待执行结束并读取时间线 | 没配模型时时间线的 ai 是「模型未配置」，执行不是成功完成。 | `tests/T-012` | `results/T-012.txt` | `logs/T-012.log` |
| T-013 | 测试要求 13 | 跑时钟工具并读取时间线 | 时间线含有类型为 tool、名称为 clock 的消息，正文是这次时钟工具返回的时间文本。 | `tests/T-013` | `results/T-013.txt` | `logs/T-013.log` |
| T-014 | 测试要求 14 | 读取同一条时间线的类型顺序 | 时间线里 system、human、tool、ai 按这个顺序出现。 | `tests/T-014` | `results/T-014.txt` | `logs/T-014.log` |
| T-015 | 测试要求 15 | 另一用户读取这条时间线 | 另一用户读取该时间线得到 401。 | `tests/T-015` | `results/T-015.txt` | `logs/T-015.log` |
| T-016 | 测试要求 16 | 读取默认压缩阈值 | `ContextWindow.DEFAULT_COMPRESS_AT` 是 0.85。 | `tests/T-016` | `results/T-016.txt` | `logs/T-016.log` |
| T-017 | 测试要求 17 | 准备一份占用低于 0.85 的上下文 | 占用低于 0.85 时不调用模型。 | `tests/T-017` | `results/T-017.txt` | `logs/T-017.log` |
| T-018 | 测试要求 18 | 准备一份占用达到 0.85 且不超过预算的上下文 | 占用达到 0.85 且估算 token 不超过预算时，模型只被调用一次，任务是 compress，准备结果的摘要等于模型返回的完整正文。 | `tests/T-018` | `results/T-018.txt` | `logs/T-018.log` |
| T-019 | 测试要求 19 | 在模型调用完成前后读取已发布摘要 | 压缩尚未完成时，已发布摘要仍是上一次完成的空摘要；模型返回完整正文后，已发布摘要等于该正文，并且不再处于压缩中。 | `tests/T-019` | `results/T-019.txt` | `logs/T-019.log` |
| T-020 | 测试要求 20 | 读取应用配置 | `application.properties` 含有 `jasagent.context.compress-at=0.85`。 | `tests/T-020` | `results/T-020.txt` | `logs/T-020.log` |
| T-021 | 测试要求 21 | 登录后读取会话上下文 | 会话上下文接口的正文含有 `compressAt`，数值是 0.85。 | `tests/T-021` | `results/T-021.txt` | `logs/T-021.log` |
| T-022 | 测试要求 22 | 读取控制台脚本 | 控制台脚本含有「中止」。 | `tests/T-022` | `results/T-022.txt` | `logs/T-022.log` |
| T-023 | 测试要求 23 | 读取控制台脚本 | 控制台脚本含有「恢复」。 | `tests/T-023` | `results/T-023.txt` | `logs/T-023.log` |
| T-024 | 测试要求 24 | 读取控制台脚本 | 控制台脚本含有 `SSE`。 | `tests/T-024` | `results/T-024.txt` | `logs/T-024.log` |
| T-025 | 测试要求 25 | 读取控制台脚本 | 控制台脚本含有 `/skill:`。 | `tests/T-025` | `results/T-025.txt` | `logs/T-025.log` |
| T-026 | 测试要求 26 | 读取控制台脚本 | 控制台脚本含有「通行证」。 | `tests/T-026` | `results/T-026.txt` | `logs/T-026.log` |
| T-027 | 测试要求 27 | 读取构建出的样式 | 构建出的样式里 `.messages` 规则不含 `180px`。 | `tests/T-027` | `results/T-027.txt` | `logs/T-027.log` |
| T-028 | 测试要求 28 | 读取控制台脚本 | 控制台脚本含有 `data-timeline":"langgraph"`。 | `tests/T-028` | `results/T-028.txt` | `logs/T-028.log` |
| T-029 | 测试要求 29 | 读取控制台脚本 | 控制台脚本不含「当前执行」。 | `tests/T-029` | `results/T-029.txt` | `logs/T-029.log` |

## 不能生成

没有不能生成。

## 结果

`results/<用例编号>.txt` 只在脚本真实执行后出现。一行，制表符分开：

用例编号	通过或失败	则	耗时毫秒	日志文件

## 执行日志

`logs/<用例编号>.log` 只在脚本真实执行后出现。一行一个检查点，制表符分开：

时间	用例编号	检查点	耗时毫秒	可观察结果
