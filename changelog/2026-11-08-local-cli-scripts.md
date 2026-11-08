# 2026-11-08 本机启动与命令入口

- 日期：2026-11-08（Asia/Shanghai）
- 做了什么：增加 `scripts/start.sh`、`scripts/stop.sh` 和仓库根的 `jasagent`。后台启动默认监听 127.0.0.1:8090，进程号和日志放在 `$HOME/.jasagent/runtime`。`jasagent` 把 user add、login、logout、use、run、status、logs、interrupt、resume、workflows、pass approve、pass deny、server 转发给本机 Java 命令行。
- 涉及模块：jasagent-app、jasagent-cli、scripts
- 怎么验证：用 Java 25 执行 `mvn -pl jasagent-app -am -DskipTests package`，测试被跳过，BUILD SUCCESS。`bash -n` 检查脚本语法。临时主目录里执行 `user add` 写出了口令哈希，未知命令退出码为 2。没有执行 `mvn test`，也没有在这台机器上留下服务进程。
