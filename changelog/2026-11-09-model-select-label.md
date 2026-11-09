# 2026-11-09 选择框显示当前模型

- 日期：2026-11-09（Asia/Shanghai）
- 做了什么：发送按钮左边的模型选择框写出当前模型名，和发送、中止、恢复同一行、同一高度。标题旁的当前模型仍保留。
- 涉及模块：jasagent-web
- 怎么验证：Java 21。`jasagent-web/frontend` 下 `npm test`，33 条通过。`./scripts/stop.sh` 后再 `./scripts/start.sh`，`127.0.0.1:18721` 健康检查是 `storage=up`。页面脚本是 `index--zjkj-Gc.js`，样式是 `index-DyuHSklB.css`。选中会话后，选择框里能看见 `deepseek-flash`。
