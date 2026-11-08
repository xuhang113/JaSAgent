# 012 命令行与页面的测试用例

## 1. anonymousSubmitIs401

要求：未登录提交执行得到 401，响应体含 `errorCode` 且不含堆栈。

看到的结果：状态码 401，响应体包含 `AUTH` 和 `executionId`，不包含 `Exception`。

## 2. runWithoutWorkspaceRejected

要求：登录后但未选工作区时，提交执行被拒绝。

看到的结果：状态码不是 202。

## 3. submitAfterWorkspaceReturns202

要求：登录并选定工作区后，提交执行返回 202 和 executionId。

看到的结果：状态码 202，响应体含有非空的 `executionId`。

## 4. listIsLimitedToCurrentUser

要求：执行列表只包含当前用户自己的执行。

看到的结果：用户甲的列表不含用户乙的执行编号。

## 5. healthIsPublic

要求：健康检查不需要登录。

看到的结果：未登录访问 `/health` 的状态码是 200。

## 6. noHttpRegistration

要求：已登录时，注册地址仍然不存在。

看到的结果：`POST /api/users` 的状态码是 404。

## 7. loginPageHasForm

要求：页面 `/login` 包含登录表单。

看到的结果：响应正文包含 `登录` 和 `password`。

## 8. cliUserAddWritesHash

要求：本机命令可以创建用户，并在该用户目录写入口令哈希。

看到的结果：命令退出码是 0，对应目录下的 `auth/password.hash` 存在。

## 9. loginPageReferencesVueBuild

要求：`/login` 的 HTML 含有 `id="app"`，并引用 `/assets/` 下的脚本。

看到的结果：响应正文同时包含 `id="app"` 和 `/assets/`。

## 10. consoleScriptHasCliAndChat

要求：控制台脚本同时含有 `CLI` 和 `对话`。

看到的结果：`/login` 引用的脚本正文同时包含 `CLI` 和 `对话`。

## 11. consoleScriptHasContextButton

要求：控制台脚本含有 `上下文`。

看到的结果：脚本正文包含 `上下文`。

## 12. consoleScriptHasPassActions

要求：控制台脚本含有 `通行证`。

看到的结果：脚本正文包含 `通行证`。

## 13. consoleScriptHasRetrieval

要求：控制台脚本含有 `溯源`。

看到的结果：脚本正文包含 `溯源`。

## 14. consoleScriptHasEvaluation

要求：控制台脚本含有 `评估`。

看到的结果：脚本正文包含 `评估`。

## 15. consoleScriptHasSseEcho

要求：控制台脚本含有 `SSE`。

看到的结果：脚本正文包含 `SSE`。

## 16. workflowsIncludeAnswerWithTool

要求：已登录用户读取 workflow 列表时，正文含有 `answer-with-tool`。

看到的结果：`GET /api/workflows` 的状态码是 200，正文包含 `answer-with-tool`。

## 17. skillsStayInsideCurrentUser

要求：只放在用户甲目录里的 skill，甲能看见，乙看不见。

看到的结果：甲的 `GET /api/skills` 包含 `alpha-skill`，乙的响应不包含 `alpha-skill`。

## 18. sessionListHidesOtherUsers

要求：用户甲新增会话后，甲的列表含有该会话编号，乙的列表不含。

看到的结果：甲的 `GET /api/sessions` 包含该 `sessionId`，乙的列表不包含。

## 19. ownerCanDeleteSession

要求：用户甲删除自己的会话后，甲的列表不再含有该编号。

看到的结果：删除的状态码是 200，随后甲的列表不包含该 `sessionId`。

## 20. otherUserCannotReadSession

要求：用户乙读取用户甲的会话得到 401。

看到的结果：状态码 401，响应体包含 `AUTH`。

## 21. otherUserCannotDeleteSession

要求：用户乙删除用户甲的会话得到 401，该会话仍留在甲的列表里。

看到的结果：删除的状态码是 401，甲的列表仍然包含该 `sessionId`。

## 22. ownerCanReadCorrection

要求：用户甲为自己的会话追加纠正 note 后，甲能读到这段文字。

看到的结果：甲的纠正 note 列表包含提交的文字。

## 23. otherUserCannotReadCorrection

要求：用户乙读取用户甲的纠正 note 得到 401。

看到的结果：状态码 401，响应体包含 `AUTH`。

## 24. executionDetailListsTools

要求：提交 `answer-with-tool` 后，详情含有工具 `clock` 和 `rag.search`。

看到的结果：详情正文包含 `"tools"`、`clock` 和 `rag.search`。

## 25. executionDetailListsPlugin

要求：同一详情含有插件 `grounding.default`。

看到的结果：详情正文包含 `grounding.default`。

## 26. passDenyIsStored

要求：对 `rag.search` 的通行证提交 `DENY` 后，详情里的决定是 `DENY`。

看到的结果：详情正文包含 `rag.search` 和 `DENY`。

## 27. contextReturnsSessionId

要求：会话上下文接口的正文含有该会话编号。

看到的结果：`GET /api/sessions/{id}/context` 的状态码是 200，正文包含该 `sessionId`。

## 28. messageCreatesExecution

要求：向自己的会话发送消息后，响应含有非空的 executionId。

看到的结果：状态码是 200，正文包含 `executionId`，且不是 `null`。

## 29. profileReturnsLogin

要求：已登录用户读取 `GET /api/me` 时，正文含有自己的登录名。

看到的结果：状态码是 200，正文包含该登录名。
