package dev.jasagent.app;

import dev.jasagent.agentpass.PassSettings;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.controller.WorkspaceList;
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
        SpringApplication application = new SpringApplication(JasagentApplication.class);
        application.setDefaultProperties(Map.of("server.address", "127.0.0.1", "server.port", "8090"));
        application.run(args);
    }

    @Bean
    LocalAuthService localAuthService(@Value("${jasagent.home:${user.home}}") String home) {
        return new LocalAuthService(Path.of(home));
    }

    @Bean
    LocalRuntime localRuntime(LocalAuthService localAuthService,
                              @Value("${jasagent.agentpass.enabled:true}") String agentpassEnabled) {
        return new LocalRuntime(localAuthService, Map.of(PassSettings.ENABLED, agentpassEnabled));
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
