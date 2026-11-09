package dev.jasagent.cli;

import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.CliSessionPointer;
import dev.jasagent.auth.LocalAuthService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * 本机命令行。用户、登录和工作区选择直接改家目录；执行、日志、工作流和通行证通过本机 HTTP 接口完成。
 */
public final class CliCommands {
    /**
     * 禁止实例化，命令都从 run 进入。
     * 副作用：没有可构造的命令对象。
     */
    private CliCommands() {
    }

    /**
     * 分发一条命令。user add 建用户，login 记住会话，logout 作废并清掉指针，use 选择工作区。status、logs、interrupt、resume、workflows、run 和 pass 走远程接口并把响应打到标准输出。
     * 副作用：可能写用户目录、会话文件、工作区选择，或向本机服务发请求。失败时把错误文本打到标准输出。
     *
     * @param args 子命令和旗标，空数组表示没有命令
     * @param home 数据根目录，会话文件放在其 .jasagent 下
     * @return 0 表示完成，1 表示执行出错，2 表示没有命令或命令无法识别
     */
    public static int run(String[] args, Path home) {
        try {
            if (args.length == 0) {
                return 2;
            }
            LocalAuthService auth = new LocalAuthService(home);
            CliSessionPointer pointer = new CliSessionPointer(home);
            if ("user".equals(args[0]) && args.length > 1 && "add".equals(args[1])) {
                auth.addUser(new AddUser(value(args, "--user"), value(args, "--password").toCharArray(), blankToNull(RemoteCommands.token(home)), true))
                        .toCompletableFuture().join();
                return 0;
            }
            if ("login".equals(args[0])) {
                Session session = auth.login(value(args, "--user"), value(args, "--password").toCharArray()).toCompletableFuture().join();
                pointer.remember(session).toCompletableFuture().join();
                System.out.println(session.login());
                return 0;
            }
            if ("logout".equals(args[0])) {
                String token = RemoteCommands.token(home);
                if (!token.isBlank()) {
                    auth.logout(token).toCompletableFuture().join();
                }
                pointer.clear().toCompletableFuture().join();
                return 0;
            }
            if ("use".equals(args[0])) {
                String token = RemoteCommands.token(home);
                Session current = auth.authenticate(token).toCompletableFuture().join();
                auth.selectWorkspace(current.token(), Path.of(value(args, "--workspace")), workspaceAllowlist(), Path.of("").toAbsolutePath())
                        .toCompletableFuture().join();
                return 0;
            }
            String base = baseUrl(home);
            String token = RemoteCommands.token(home);
            RemoteCommands remote = new RemoteCommands();
            if ("status".equals(args[0])) {
                System.out.println(remote.call("GET", base + "/api/executions/" + encode(value(args, "--execution")), token, null));
                return 0;
            }
            if ("logs".equals(args[0])) {
                System.out.println(remote.call("GET", base + "/api/executions/" + encode(value(args, "--execution")) + "/logs", token, null));
                return 0;
            }
            if ("interrupt".equals(args[0])) {
                System.out.println(remote.call("POST", base + "/api/executions/" + encode(value(args, "--execution")) + "/interrupt", token, "{}"));
                return 0;
            }
            if ("resume".equals(args[0])) {
                System.out.println(remote.call("POST", base + "/api/executions/" + encode(value(args, "--execution")) + "/resume", token, "{}"));
                return 0;
            }
            if ("workflows".equals(args[0])) {
                System.out.println(remote.call("GET", base + "/api/workflows", token, null));
                return 0;
            }
            if ("run".equals(args[0])) {
                String body = "{\"workflowId\":\"" + json(value(args, "--workflow")) + "\",\"input\":\"" + json(value(args, "--input")) + "\"}";
                System.out.println(remote.call("POST", base + "/api/executions", token, body));
                return 0;
            }
            if ("pass".equals(args[0]) && args.length > 1 && ("approve".equals(args[1]) || "deny".equals(args[1]))) {
                String decision = "approve".equals(args[1]) ? "ALLOW" : "DENY";
                String body = "{\"decision\":\"" + decision + "\"}";
                String url = base + "/api/executions/" + encode(value(args, "--execution")) + "/passes/" + encode(value(args, "--pass"));
                System.out.println(remote.call("POST", url, token, body));
                return 0;
            }
            System.out.println("未知命令");
            return 2;
        } catch (RuntimeException ex) {
            System.out.println(message(ex));
            return 1;
        } catch (Exception ex) {
            System.out.println(message(ex));
            return 1;
        }
    }

    /**
     * 决定命令行访问的服务地址。优先用 JASAGENT_BASE_URL，其次读运行目录里的 address 文件，都没有时用 http://127.0.0.1:18721。结果去掉末尾一个斜杠。
     *
     * @param home 用来定位默认运行目录的家目录
     * @return 不带末尾斜杠的基址
     */
    private static String baseUrl(Path home) throws Exception {
        String configured = System.getenv("JASAGENT_BASE_URL");
        if (configured != null && !configured.isBlank()) {
            return stripSlash(configured.trim());
        }
        Path address = runtimeDir(home).resolve("address");
        if (Files.isRegularFile(address)) {
            String line = Files.readString(address).trim();
            if (line.startsWith("http://") || line.startsWith("https://")) {
                return stripSlash(line);
            }
        }
        return "http://127.0.0.1:18721";
    }

    /**
     * 定位运行时目录。设置了 JASAGENT_RUNTIME_DIR 就用它，否则是家目录下的 .jasagent/runtime。
     *
     * @param home 默认家目录
     * @return 存放 address 等运行文件的目录
     */
    private static Path runtimeDir(Path home) {
        String configured = System.getenv("JASAGENT_RUNTIME_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return home.resolve(".jasagent").resolve("runtime");
    }

    /**
     * 读取允许选择的工作区。先看环境变量 JASAGENT_WORKSPACES，没有再看系统属性 jasagent.workspaces，按逗号拆开并丢掉空白项。
     *
     * @return 允许的路径，未配置时是空列表
     */
    private static List<Path> workspaceAllowlist() {
        String raw = System.getenv("JASAGENT_WORKSPACES");
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty("jasagent.workspaces", "");
        }
        List<Path> paths = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return paths;
        }
        for (String item : raw.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                paths.add(Path.of(trimmed));
            }
        }
        return paths;
    }

    /**
     * 取旗标后面的那一个参数。旗标不存在或已经是最后一个词时抛出缺少该旗标。
     *
     * @param args 完整命令行
     * @param name 旗标，例如 --user
     * @return 旗标后面的原文字
     */
    private static String value(String[] args, String name) {
        for (int index = 0; index < args.length - 1; index++) {
            if (name.equals(args[index])) {
                return args[index + 1];
            }
        }
        throw new IllegalArgumentException("缺少 " + name);
    }

    /**
     * 把空白令牌收成 null，这样建用户时可以表示没有管理员令牌。
     *
     * @param value 令牌原文，可以是 null
     * @return 原值；null 或空白时为 null
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 按 UTF-8 做 URL 编码，并把空格留下的加号改成 %20，用于执行编号和通行证编号。
     *
     * @param value 要放进路径的原文
     * @return 编码后的路径片段
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 把要嵌进 JSON 字符串的文本转义反斜杠、引号、换行、回车和制表符。
     *
     * @param value 工作流编号或用户输入
     * @return 可放进双引号的文本
     */
    private static String json(String value) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(current);
            }
        }
        return builder.toString();
    }

    /**
     * 去掉基址末尾的一个斜杠，避免和后面的接口路径叠成双斜杠。
     *
     * @param value 基址或 address 文件中的一行
     * @return 没有末尾斜杠的地址
     */
    private static String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 剥掉补全异常的包装，取出给用户看的错误文本。没有消息时用异常类的简名。
     *
     * @param ex 命令执行中抛出的异常
     * @return 一行错误说明
     */
    private static String message(Throwable ex) {
        Throwable current = ex;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        String text = current.getMessage();
        return text == null || text.isBlank() ? current.getClass().getSimpleName() : text;
    }
}
