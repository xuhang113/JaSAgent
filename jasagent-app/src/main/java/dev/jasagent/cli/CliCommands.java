package dev.jasagent.cli;

import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.CliSessionPointer;
import dev.jasagent.auth.LocalAuthService;

import java.nio.file.Path;

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
                auth.addUser(new AddUser(value(args, "--user"), value(args, "--password").toCharArray(), null, true)).toCompletableFuture().join();
                return 0;
            }
            if ("login".equals(args[0])) {
                Session session = auth.login(value(args, "--user"), value(args, "--password").toCharArray()).toCompletableFuture().join();
                pointer.remember(session).toCompletableFuture().join();
                System.out.println(session.login());
                return 0;
            }
            if ("logout".equals(args[0])) {
                pointer.clear().toCompletableFuture().join();
                return 0;
            }
            if ("use".equals(args[0])) {
                String token = RemoteCommands.token(home);
                Session current = auth.authenticate(token).toCompletableFuture().join();
                auth.selectWorkspace(current.token(), Path.of(value(args, "--workspace")), java.util.List.of(Path.of(value(args, "--workspace"))), Path.of("").toAbsolutePath()).toCompletableFuture().join();
                return 0;
            }
            String base = System.getenv().getOrDefault("JASAGENT_BASE_URL", "http://127.0.0.1:8090");
            String token = RemoteCommands.token(home);
            RemoteCommands remote = new RemoteCommands();
            if ("status".equals(args[0])) {
                System.out.println(remote.call("GET", base + "/api/executions/" + value(args, "--execution"), token, null));
                return 0;
            }
            if ("logs".equals(args[0])) {
                System.out.println(remote.call("GET", base + "/api/executions/" + value(args, "--execution") + "/logs", token, null));
                return 0;
            }
            if ("interrupt".equals(args[0])) {
                System.out.println(remote.call("POST", base + "/api/executions/" + value(args, "--execution") + "/interrupt", token, "{}"));
                return 0;
            }
            if ("resume".equals(args[0])) {
                System.out.println(remote.call("POST", base + "/api/executions/" + value(args, "--execution") + "/resume", token, "{}"));
                return 0;
            }
            if ("workflows".equals(args[0])) {
                System.out.println("answer-with-tool");
                return 0;
            }
            if ("run".equals(args[0])) {
                String body = "{\"workflowId\":\"" + value(args, "--workflow") + "\",\"input\":\"" + value(args, "--input") + "\"}";
                System.out.println(remote.call("POST", base + "/api/executions", token, body));
                return 0;
            }
            return 2;
        } catch (RuntimeException ex) {
            System.out.println(ex.getMessage());
            return 1;
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
            return 1;
        }
    }

    private static String value(String[] args, String name) {
        for (int index = 0; index < args.length - 1; index++) {
            if (name.equals(args[index])) {
                return args[index + 1];
            }
        }
        throw new IllegalArgumentException("缺少 " + name);
    }
}
