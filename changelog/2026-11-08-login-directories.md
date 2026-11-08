# 2026-11-08 登录与目录

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：实现本机第一个用户和后续用户的创建规则、PBKDF2-HMAC-SHA256 口令、权限 600 的会话文件、用户目录与工作区目录、规则和技能按用户目录覆盖装载。
- 涉及模块：jasagent-api、jasagent-auth
- 怎么验证：在仓库根目录执行 `mvn test`，`LoginDirectoryTest` 18 项通过，失败 0，错误 0，跳过 0。验收脚本 `requirements/001-login-directories/tests/001-login-directories.sh` 退出码为 0。
