package dev.jasagent.auth;

import dev.jasagent.api.auth.Session;
import dev.jasagent.api.concurrent.VirtualIo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

public final class CliSessionPointer {
    private final Path homeRoot;

    public CliSessionPointer(Path homeRoot) {
        this.homeRoot = homeRoot.toAbsolutePath().normalize();
    }

    public Path file() {
        return homeRoot.resolve(".jasagent").resolve("cli-session");
    }

    public CompletionStage<Void> remember(Session session) {
        return VirtualIo.supply(() -> {
            String body = "login=" + session.login() + "\ntoken=" + session.token() + "\n";
            OwnerFiles.write(file(), body);
            return null;
        });
    }

    public CompletionStage<Void> clear() {
        return VirtualIo.supply(() -> {
            Files.deleteIfExists(file());
            return null;
        });
    }

    public CompletionStage<String> readLogin() {
        return VirtualIo.supply(() -> {
            Path pointer = file();
            if (!Files.isRegularFile(pointer)) {
                return "";
            }
            for (String line : Files.readString(pointer, StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("login=")) {
                    return line.substring("login=".length());
                }
            }
            return "";
        });
    }
}
