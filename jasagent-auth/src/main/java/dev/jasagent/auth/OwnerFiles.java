package dev.jasagent.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * 把只应属主可见的认证材料写成仅属主可读写的文件，需要时把目录收成仅属主可进入。
 */
final class OwnerFiles {
    /**
     * 普通敏感文件的 POSIX 权限：只有属主可读可写，组和其他人没有任何权限。
     */
    static final Set<PosixFilePermission> FILE_600 = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    /**
     * 敏感目录的 POSIX 权限：只有属主可读、可写、可进入。
     */
    static final Set<PosixFilePermission> DIR_700 = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    /**
     * 禁止实例化，权限集合和写入动作都只通过静态成员使用。
     */
    private OwnerFiles() {
    }

    /**
     * 先把全文写入同级临时文件并收成仅属主可读写，再原子替换目标文件，并再次套上同样的权限。
     * 副作用是创建缺失的父目录、替换目标内容，以及改动临时文件和目标文件的 POSIX 权限。
     *
     * @param file 最终要落地的文件
     * @param content 按 UTF-8 写入的全文
     */
    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(temporary, FILE_600);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.setPosixFilePermissions(file, FILE_600);
    }
}
