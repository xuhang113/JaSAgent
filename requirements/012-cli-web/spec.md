# 012 命令行与页面

## 这次要改什么

现状是命令行、REST 和手写 HTML 已经接在同一个执行服务上。页面仍是拼接出来的 `/login`、`/`、`/run`、`/executions/{id}`。登录、选定工作区、提交、查询、中止、恢复、日志、SSE、检索结果和评估字符串能从这些页面碰到，但登录后不是管理控制台，CLI 面板和对话区也不能同时放在一个布局里。workflow 列表、当前用户可见的 skill、按登录用户隔离的对话会话、纠正 note、通行证 ALLOW 或 DENY、当前执行包含的插件和工具、会话上下文，都还没有给页面用的接口。

这次把页面换成 Vue 3、Vite 和 Element Plus。前端放在 `jasagent-web/frontend`，构建结果交给 Spring 托管。Spring 继续提供已有 REST，并补上控制台缺的那几项查询和写入。不使用 Spring AI。登录后的控制台同时放下 CLI 面板和对话区。对话输入 `/` 时弹出当前用户可见的 skill，选中后插入 `/skill:名称` 引用。上下文是按钮，点击后打开面板。会话按当前登录用户新增、删除和切换，用户不能看到另一用户的会话和执行。

不改命令行子命令，不改未登录 401、未选工作区不能提交、提交返回 202、执行列表只含本人、健康检查公开、没有 HTTP 注册。不新增数据库表，不把密钥写进仓库。

## 模块

`jasagent-web` 增加独立前端目录，Java 侧只把构建出的 `index.html` 和静态资源交出去。`jasagent-api` 增加控制台端口。`jasagent-app` 在现有执行服务上实现这个端口。`jasagent-controller` 把端口暴露成登录后的 REST。这四个模块可以继续使用 Spring Boot 3。其余模块不依赖 Spring，也不引入 Spring AI。

## 改动地方

`/login` 返回 Vue 构建的 HTML，正文里仍有登录表单。`/`、`/run`、`/executions/{id}` 同样返回这份壳，由前端路由显示控制台。静态资源放在 `/assets/`，未登录也可以取，否则登录页打不开。

控制台调用这些接口。已经存在的保持原语义：`POST /api/executions`、`GET /api/executions`、`GET /api/executions/{id}`、`GET /api/executions/{id}/logs`、`POST /api/executions/{id}/interrupt`、`POST /api/executions/{id}/resume`、`GET /api/executions/{id}/events`、`POST /api/workspace`、`POST /api/rag/search`。

这次补上：`GET /api/me`、`GET /api/workspaces`、`GET /api/workflows`、`GET /api/skills`、`GET /api/sessions`、`POST /api/sessions`、`GET /api/sessions/{id}`、`DELETE /api/sessions/{id}`、`POST /api/sessions/{id}/messages`、`GET /api/sessions/{id}/context`、`POST /api/sessions/{id}/corrections`、`GET /api/sessions/{id}/corrections`、`POST /api/executions/{id}/passes/{passId}`。未登录访问这些地址得到 401。

执行详情在原有队列、日志、检索、通行证、评估之外，带上这次工作流包含的工具和插件。`answer-with-tool` 的工具是 `clock` 与 `rag.search`，grounding 打开时带上插件 `grounding.default`。外部工具和插件各有一条带 `passId` 的通行证。`clock` 仍是内部工具，不申请通行证。`POST` 的 `decision` 只接受 `ALLOW` 或 `DENY`，写入后详情能读到新决定。

技能从当前用户目录和工作区目录的 `jasagent/skills` 装载，同名时用户目录优先。会话文件写在该用户目录的 `conversations/`。纠正 note 写在该用户目录的 `corrections/{sessionId}/`。另一用户的编号在当前用户目录里找不到，接口按未授权拒绝。

## 数据库

执行记录继续用工作区里的 H2 文件。这次不新增表。会话和纠正 note 是该用户目录里的文件。

## 中间件

HTTP 仍只绑定 `127.0.0.1:18721`。不新增消息队列或检索服务。默认测试不访问外网，也不调用真实模型。

## 测试要求

1. 未登录提交执行得到 401，响应体含 `errorCode` 且不含堆栈。
2. 登录后但未选工作区时，提交执行被拒绝。
3. 登录并选定工作区后，提交执行返回 202 和 executionId。
4. 执行列表只包含当前用户自己的执行。
5. 健康检查不需要登录。
6. 已登录时，注册地址仍然不存在。
7. 页面 `/login` 包含登录表单。
8. 本机命令可以创建用户，并在该用户目录写入口令哈希。
9. `/login` 的 HTML 含有 `id="app"`，并引用 `/assets/` 下的脚本。
10. 控制台脚本同时含有 `CLI` 和 `对话`。
11. 控制台脚本含有 `上下文`。
12. 控制台脚本含有 `通行证`。
13. 控制台脚本含有 `溯源`。
14. 控制台脚本含有 `评估`。
15. 控制台脚本含有 `SSE`。
16. 已登录用户读取 workflow 列表时，正文含有 `answer-with-tool`。
17. 只放在用户甲目录里的 skill，甲能看见，乙看不见。
18. 用户甲新增会话后，甲的列表含有该会话编号，乙的列表不含。
19. 用户甲删除自己的会话后，甲的列表不再含有该编号。
20. 用户乙读取用户甲的会话得到 401。
21. 用户乙删除用户甲的会话得到 401，该会话仍留在甲的列表里。
22. 用户甲为自己的会话追加纠正 note 后，甲能读到这段文字。
23. 用户乙读取用户甲的纠正 note 得到 401。
24. 提交 `answer-with-tool` 后，详情含有工具 `clock` 和 `rag.search`。
25. 同一详情含有插件 `grounding.default`。
26. 对 `rag.search` 的通行证提交 `DENY` 后，详情里的决定是 `DENY`。
27. 会话上下文接口的正文含有该会话编号。
28. 向自己的会话发送消息后，响应含有非空的 executionId。
29. 已登录用户读取 `GET /api/me` 时，正文含有自己的登录名。

## 还没定

无。
