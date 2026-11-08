package dev.jasagent.app;

import dev.jasagent.agentpass.PassSettings;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.cli.CliCommands;
import dev.jasagent.controller.StorageStatus;
import dev.jasagent.controller.WorkspaceList;
import dev.jasagent.core.ContextWindow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@SpringBootApplication(scanBasePackages = {"dev.jasagent.app", "dev.jasagent.controller", "dev.jasagent.web"})
public class JasagentApplication {
    public static void main(String[] args) {
        if (dispatchesToCli(args)) {
            int code = CliCommands.run(args, Path.of(System.getProperty("user.home")));
            System.exit(code);
        }
        SpringApplication application = new SpringApplication(JasagentApplication.class);
        application.setDefaultProperties(Map.of("server.address", "127.0.0.1", "server.port", "18721"));
        application.run(serverArgs(args));
    }

    private static boolean dispatchesToCli(String[] args) {
        return args.length > 0 && !"server".equals(args[0]) && !args[0].startsWith("-");
    }

    private static String[] serverArgs(String[] args) {
        if (args.length > 0 && "server".equals(args[0])) {
            return Arrays.copyOfRange(args, 1, args.length);
        }
        return args;
    }

    @Bean
    LocalAuthService localAuthService(@Value("${jasagent.home:${user.home}}") String home) {
        return new LocalAuthService(Path.of(home));
    }

    @Bean
    LocalRuntime localRuntime(LocalAuthService localAuthService,
                              @Value("${jasagent.agentpass.enabled:true}") String agentpassEnabled,
                              @Value("${jasagent.context.compress-at:0.85}") String compressAt) {
        return new LocalRuntime(localAuthService, Map.of(
                PassSettings.ENABLED, agentpassEnabled,
                ContextWindow.COMPRESS_AT, compressAt));
    }

    @Bean
    StorageStatus storageStatus(LocalRuntime localRuntime) {
        return localRuntime::storageReachable;
    }

    @Bean
    WorkspaceList workspaceList(@Value("${jasagent.workspaces:}") String raw) {
        if (raw == null || raw.isBlank()) {
            return new WorkspaceList(List.of());
        }
        List<Path> paths = Arrays.stream(raw.split(",")).map(String::trim).filter(value -> !value.isEmpty()).map(Path::of).toList();
        return new WorkspaceList(paths);
    }
}
