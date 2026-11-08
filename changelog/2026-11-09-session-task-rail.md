# 2026-11-09 会话页任务列表

- 日期：2026-11-09（Asia/Shanghai）
- 做了什么：选中会话后，正在执行的子 agent 挂在对话区顶部。历史的、不在执行的子 agent 默认不显示，点「全部」才打开这条会话的子 agent 列表。右栏默认按进行中、待看、完成分组列出短标题，点一行看执行或子 agent 的摘要。Trace、工具、上下文、日志仍用 tab 打开，默认不占右栏。溯源、评估、通行证、CLI 仍在抽屉。主按钮仍是黑底白字、圆角 8px。
- 涉及模块：jasagent-web
- 怎么验证：`jasagent-web/frontend` 下 `npm test`，24 条通过。`JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -pl jasagent-app -am test -Dtest=ConsolePageTest,ConsoleSessionPageTest -Dsurefire.failIfNoSpecifiedTests=false`，ConsolePageTest 30、ConsoleSessionPageTest 25，失败 0。重新打包后 `./scripts/stop.sh` 再 `./scripts/start.sh`，`127.0.0.1:18721` 的页面脚本是 `index-BJ_Hz_7q.js`。
