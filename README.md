# JaSAgent

本仓库是 `dev.jasagent:jasagent:0.1.0`。总纲在全部需求完成时写全。

## CLI

仓库根的 `jasagent` 把子命令转发给本机 Java 命令行。需要本机的 Java 21 或更高版本。脚本先用 `java` 命令；版本不够时，再找本机已经安装的 Java 21 或更高版本。仍然没有，就说明需要 Java 21。

路径取当前用户的主目录，不写死某一个用户名。进程号和日志写在运行时目录 `$HOME/.jasagent/runtime`，不写进仓库。环境变量 `JASAGENT_RUNTIME_DIR` 可以改这个目录。

### 启动和停止

```bash
./scripts/start.sh
./scripts/stop.sh
```

`start.sh` 在后台启动服务，默认地址是 `http://127.0.0.1:8090`。已经在运行时打印该地址并以 0 退出。`stop.sh` 按进程号停止。没有在运行时以 0 退出。两条脚本都可以重复执行。

还没有可执行 jar，或源码比 jar 新时，启动和 `jasagent` 会在仓库根执行 `mvn -pl jasagent-app -am -DskipTests package`，不跑测试。

`JASAGENT_HOST` 和 `JASAGENT_PORT` 可以改 `start.sh` 的监听地址。命令行访问服务时，优先用 `JASAGENT_BASE_URL`；没有设置时，读取运行时目录里上次启动写下的地址，再没有就用 `http://127.0.0.1:8090`。

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

`workflows`、`run`、`status`、`logs`、`interrupt`、`resume`、`pass approve`、`pass deny` 调用已经启动的服务。`server` 在前台启动，默认监听 `127.0.0.1:8090`。后台启停用上面两个脚本。

不带参数时，`./jasagent` 打印上述用法。
