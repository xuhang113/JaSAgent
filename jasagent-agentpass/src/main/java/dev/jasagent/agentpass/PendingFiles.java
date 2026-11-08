package dev.jasagent.agentpass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

final class PendingFiles {
    private static final Set<PosixFilePermission> FILE_600 = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> DIR_700 = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private PendingFiles() {
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.setPosixFilePermissions(file.getParent(), DIR_700);
        Path temporary = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(temporary, FILE_600);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.setPosixFilePermissions(file, FILE_600);
    }
}
