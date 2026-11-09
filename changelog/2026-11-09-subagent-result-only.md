# 2026-11-09 只有结果物才开子 agent

- 日期：2026-11-09（Asia/Shanghai）
- 做了什么：问候、解释和一句话能答完的留在主会话，不再记子 agent。要工作区事实或文件结果时才开子 agent。右栏改成主会话在上、子任务缩进在下，并标进行中、完成或失败。点进子任务只看那一次的对话和结果，可以返回主会话。
- 涉及模块：jasagent-app、jasagent-web
- 怎么验证：`SubAgentNeedTest` 2 条、`ConsoleSessionPageTest` 32 条通过。前端 `npm test` 35 条通过。18721 上「你好」没有子任务；「工作区内有多少文件」出现缩进的子任务，点进去能看到 510 个文件并返回。页面资源是 `index-D2Lmkrrf.js`、`index-Bq6Gay9v.css`。
