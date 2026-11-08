# 2026-11-08 合入按钮样式与交互动效

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：把会话页按钮样式和交互动效合进默认分支。主操作按钮是背景 #1b1b18、白字、圆角 8px、字号 12px。次操作是浅边框。DENY 背景是 #b42318。中止是浅红底。悬停 0.15 秒，按压 0.1 秒，展开和折叠 0.2 秒，新消息只做透明度。
- 涉及模块：jasagent-web
- 怎么验证：`JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test`，以及 `jasagent-web/frontend` 下 `npm test`。
