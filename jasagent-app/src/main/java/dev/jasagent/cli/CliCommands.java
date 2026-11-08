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

public final class CliCommands {
    private CliCommands() {
    }

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
        return "http://127.0.0.1:8090";
    }

    private static Path runtimeDir(Path home) {
        String configured = System.getenv("JASAGENT_RUNTIME_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return home.resolve(".jasagent").resolve("runtime");
    }

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

    private static String value(String[] args, String name) {
        for (int index = 0; index < args.length - 1; index++) {
            if (name.equals(args[index])) {
                return args[index + 1];
            }
        }
        throw new IllegalArgumentException("缺少 " + name);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

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

    private static String stripSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String message(Throwable ex) {
        Throwable current = ex;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        String text = current.getMessage();
        return text == null || text.isBlank() ? current.getClass().getSimpleName() : text;
    }
}
