package dev.jasagent.agentpass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * 把待审批通行证原子写成仅属主可读写的文件，并把所在目录收成仅属主可进入。
 */
final class PendingFiles {
    /**
     * 待审批文件的权限：只有属主可读可写。
     */
    private static final Set<PosixFilePermission> FILE_600 = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    /**
     * 待审批目录的权限：只有属主可读、可写、可进入。
     */
    private static final Set<PosixFilePermission> DIR_700 = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    /**
     * 禁止实例化，落盘只通过静态写入完成。
     */
    private PendingFiles() {
    }

    /**
     * 先收紧父目录，再把正文写入临时文件并原子替换目标，两份文件都只留属主读写。副作用是创建目录、替换文件并设置 POSIX 权限。
     *
     * @param file 最终的待审批文件
     * @param content 按 UTF-8 写入的通行证正文
     */
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
