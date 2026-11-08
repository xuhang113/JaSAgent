# 012 命令行与页面

## 这次要改什么

现状是登录、执行、工作流、工具、检索、中止和评估都有了实现，但还没有统一入口。这次把命令行、页面和 REST 接到同一个执行服务上。不实现第 17 节明确不做的事项。

## 模块

这次新增 `jasagent-controller`、`jasagent-web`、`jasagent-cli`，并由 `jasagent-app` 组装。这四个模块可以使用 Spring Boot 3。其余业务模块仍然不依赖 Spring。除 app 外，controller、web、cli 的业务依赖只指向 `jasagent-api`。

## 改动地方

HTTP 绑定 `127.0.0.1:8090`。`/health` 和 `/login` 不需要登录。没有 HTTP 注册接口。未登录访问业务接口返回 401，响应体含 `executionId` 和 `errorCode`，不含堆栈。

登录成功后发 HttpOnly 会话 cookie。必须先选定允许的工作区，否则提交执行被拒绝。提交返回 202 和 executionId。列表只含当前用户的执行。

页面有 `/login`、`/`、`/run`、`/executions/{id}`。详情页能看到队列、日志、评估，并能中止和恢复，进度用 SSE。

命令行支持 `server`、`user add`、`login`、`logout`、`use`、`run`、`status`、`logs`、`interrupt`、`resume`、`workflows`。`run` 默认附着日志，`--detach` 时打印 executionId 后退出。

仓库带上 `rules/base.md` 和 `skills/research/SKILL.md`，只写用法，不写密钥。README 用中文写总纲。

两个供应方都没配置时，示例执行使用离线回答，不访问网络，也不把离线回答登记成第三家模型。DeepSeek 和 Gateway 一旦配了环境变量，仍走原来的路由。

## 数据库

执行记录继续用工作区里的 H2 文件。这次不新增表。

## 中间件

HTTP 服务器只绑本机。默认测试不访问外网。

## 测试要求

1. 未登录提交执行得到 401，响应体含 `errorCode` 且不含堆栈。
2. 登录后但未选工作区时，提交执行被拒绝。
3. 登录并选定工作区后，提交执行返回 202 和 executionId。
4. 执行列表只包含当前用户自己的执行。
5. 健康检查不需要登录。
6. 已登录时，注册地址仍然不存在。
7. 页面 `/login` 包含登录表单。
8. 本机命令可以创建用户，并在该用户目录写入口令哈希。

## 还没定

无。
