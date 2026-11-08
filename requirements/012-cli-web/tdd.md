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
