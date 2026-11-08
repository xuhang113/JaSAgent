# 001 登录与目录的测试用例

## 1. firstUserCanBeCreated

要求：没有任何用户时，可以创建第一个用户，并在该登录名对应的用户目录写入口令哈希文件。

看到的结果：`addUser` 成功返回，返回的目录下存在非空的 `auth/password.hash`。

## 2. secondUserRejectedWithoutLocalSession

要求：已经存在用户时，缺少有效本机会话就拒绝再创建用户。

看到的结果：第二次 `addUser` 以错误码 `AUTH` 失败，第二个登录名的口令文件不存在。

## 3. secondUserAcceptedWithLocalSession

要求：已经存在用户时，持有有效本机会话可以再创建用户。

看到的结果：带本机标志和有效令牌的 `addUser` 成功，第二个用户的口令文件存在。

## 4. passwordUsesPbkdf2AndRandomSalt

要求：口令哈希使用 PBKDF2-HMAC-SHA256，迭代次数为 210000，盐长 16 字节，同一口令两次写入的盐不同，正确口令可以通过独立重算校验，错误口令不能通过。

看到的结果：两个哈希的盐不同，迭代次数都是 210000，用测试里单独实现的 PBKDF2 能重算通过，错误口令重算不相等，哈希正文不包含原始口令。

## 5. passwordFileMode600

要求：口令哈希文件的权限是仅所有者可读写。

看到的结果：`auth/password.hash` 的 POSIX 权限集合恰好是所有者读和所有者写。

## 6. userDirectoryFollowsLoginName

要求：两个不同的登录名各自得到主目录下 `<登录名>/jasagent`，路径随登录名变化。

看到的结果：两个目录路径不同，并且分别等于主目录加上对应登录名和 `jasagent`。

## 7. workspaceDirectoryUsesJasagent

要求：工作区目录等于该工作区路径下的 `jasagent`。

看到的结果：返回路径等于传入工作区路径再追加 `jasagent`。

## 8. emptyAllowlistOnlyCurrentDirectory

要求：允许的工作区列表为空时，只有当前工作目录可以通过检查。

看到的结果：当前工作目录通过检查，另一个绝对路径以错误码 `AUTH` 被拒绝。

## 9. workspaceOutsideAllowlistRejected

要求：允许的工作区列表不为空时，列表之外的路径不能通过检查。

看到的结果：列表中的路径通过检查，列表外的路径以错误码 `AUTH` 被拒绝。

## 10. userCannotReadAnotherUsersDirectory

要求：一个用户解析另一个用户的目录时被拒绝，错误码为 `AUTH`。

看到的结果：用甲的登录名解析乙的 `auth/password.hash` 抛出 `AUTH`，用甲解析自己的同一相对路径则得到甲目录内部的路径。

## 11. loginSessionFileMode600

要求：登录成功后，会话文件位于该用户目录的 `auth/sessions`，权限是仅所有者可读写。

看到的结果：会话文件在该用户的 `auth/sessions/<sessionId>`，权限恰好是所有者读和所有者写，文件中含有本次令牌。

## 12. logoutInvalidatesToken

要求：退出登录后，原来的令牌不能再通过校验。

看到的结果：退出后再次校验该令牌以错误码 `AUTH` 失败，会话文件已删除。

## 13. sameRuleIdUserWins

要求：同一规则标识同时出现在工作区和用户目录时，装载结果采用用户目录的正文。

看到的结果：标识 `base` 的正文等于用户目录文件中的正文。

## 14. sameSkillNameUserWins

要求：同一技能名称同时出现在工作区和用户目录时，装载结果采用用户目录的正文。

看到的结果：名称 `research` 的正文等于用户目录技能文件中的正文。

## 15. workspaceOnlyRuleRemains

要求：只存在于工作区的规则仍然会出现在装载结果里。

看到的结果：只写在工作区的规则标识能被查到，正文等于工作区文件。

## 16. selectWorkspaceStoredOnSession

要求：选定允许的工作区之后，会话能读回该工作区的绝对路径。

看到的结果：重新校验令牌得到的工作区等于该路径的绝对规范化形式。

## 17. cliPointerMode600

要求：本机会话指针写入主目录 `.jasagent/cli-session`，权限是仅所有者可读写，并且内容对应当前登录名。

看到的结果：指针文件权限恰好是所有者读和所有者写，文件中含有本次登录名和令牌，清除后文件不存在。

## 18. loginNameRejectsPathSeparators

要求：登录名包含路径分隔符时拒绝创建，并且主目录外不会出现新的口令文件。

看到的结果：创建以错误码 `AUTH` 失败，主目录的父目录下不存在被路径穿越写出来的 `password.hash`。
