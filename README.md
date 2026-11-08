# JaSAgent

本仓库是 `dev.jasagent:jasagent:0.1.0`。总纲在全部需求完成时写全。

## CLI

仓库根的 `jasagent` 把子命令转发给本机 Java 命令行。

### 启动和停止

在仓库根目录执行：

```bash
./scripts/start.sh
./scripts/stop.sh
```

`start.sh` 在后台启动服务。`JASAGENT_HOST` 默认是 `127.0.0.1`，`JASAGENT_PORT` 默认是 `18721`，与 `jasagent-app/src/main/resources/application.properties` 里的 `server.address`、`server.port` 相同。脚本把它们写成 `--server.address` 和 `--server.port`。成功后打印 `http://127.0.0.1:18721`。页面是 `http://127.0.0.1:18721/`，登录地址是 `http://127.0.0.1:18721/login`。还没有登录时，打开页面会重定向到登录地址。已经在运行时打印地址文件里的地址；文件不存在或没有内容时打印 `http://127.0.0.1:18721`，并以 0 退出。`stop.sh` 按进程号停止。没有在运行时以 0 退出。两条脚本都可以重复执行。端口不是 1 到 65535 的数字时，`start.sh` 以 1 退出。

规格是 Java 21。脚本先用 `java` 命令。主版本号大于等于 21 就用它。当前 `java` 是 17 时不用它，再找本机已经安装的 Java 21 或更高：先看 `JAVA_HOME/bin/java`，再看 `/usr/libexec/java_home -v` 的 21、22、23、24、25，然后看 `/opt/homebrew/opt` 和 `/usr/local/opt` 里的 `openjdk@21`、`openjdk@22`、`openjdk@23`、`openjdk@24`、`openjdk@25`、`openjdk`。主版本号不到 21 的都跳过。找到就改用那一个，并在标准错误写明当前 `java` 不能运行、改用了哪一个版本和路径。仍然没有，就以 1 退出，说明需要 Java 21 或更高，不会改用 Java 17 或更低版本。没有 `java` 命令时，先看 `JAVA_HOME/bin/java`，后面的查找相同。

还没有可执行 jar，或源码比 jar 新时，启动和 `jasagent` 会在仓库根执行 `mvn -pl jasagent-app -am -DskipTests package`，不跑测试。找不到 `mvn` 时以 1 退出。

路径取当前用户的主目录，不写死某一个用户名。进程号写在 `$HOME/.jasagent/runtime/server.pid`，日志写在 `$HOME/.jasagent/runtime/server.log`，这次启动的地址写在 `$HOME/.jasagent/runtime/address`。这些文件不写进仓库。环境变量 `JASAGENT_RUNTIME_DIR` 可以改这个目录。还没在运行、准备拉起时清空日志。服务没有留在后台时，停掉刚拉起的进程，删掉进程号文件，并以 1 退出；日志和地址文件留着。`stop.sh` 停掉之后也删掉进程号文件，日志和地址文件留着。没能停掉时以 1 退出。这两种失败都会在标准错误里带上日志路径。进程号对不上本服务时，视为没有在运行，并删掉进程号文件。

本机命令在仓库根执行 `./jasagent`。命令行访问服务时，优先用 `JASAGENT_BASE_URL`；没有设置时，读取地址文件，再没有就用 `http://127.0.0.1:18721`。子命令见下一节。

### 命令

在仓库根目录执行：

```bash
./jasagent user add --user <登录名> --password <口令>
./jasagent login --user <登录名> --password <口令>
./jasagent logout
./jasagent use --workspace <工作区路径>
./jasagent workflows
./jasagent run --workflow <workflowId> --input <文本>
./jasagent status --execution <executionId>
./jasagent logs --execution <executionId>
./jasagent interrupt --execution <executionId>
./jasagent resume --execution <executionId>
./jasagent pass approve --execution <executionId> --pass <passId>
./jasagent pass deny --execution <executionId> --pass <passId>
./jasagent server
```

`<口令>` 只是占位符。不要把真实口令或密钥写进仓库。

`user add`、`login`、`logout`、`use` 走本机目录。还没有用户时可以创建第一个；已经有用户时，`user add` 沿用现有规则，只接受当前已登录的本机命令。允许的工作区列表为空时，`use` 只接受当前工作目录。列表来自环境变量 `JASAGENT_WORKSPACES`，多项用逗号分开。

`workflows`、`run`、`status`、`logs`、`interrupt`、`resume`、`pass approve`、`pass deny` 调用已经启动的服务。`server` 在前台启动，默认监听 `127.0.0.1:18721`，不写进程号和日志。后台启停用上面两个脚本。

不带参数时，`./jasagent` 打印上述用法。
