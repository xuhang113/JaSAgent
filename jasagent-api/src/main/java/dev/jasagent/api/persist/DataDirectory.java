package dev.jasagent.api.persist;

import java.nio.file.Path;

public final class DataDirectory {
    private DataDirectory() {
    }

    public static Path of(Path workspace) {
        return workspace.toAbsolutePath().normalize().resolve(".jasagent").resolve("data");
    }
}
