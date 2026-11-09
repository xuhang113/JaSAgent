package dev.jasagent.auth;

import dev.jasagent.api.auth.Session;
import dev.jasagent.api.concurrent.VirtualIo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/**
 * 在家目录下记住本机命令行当前使用的登录名和会话令牌。
 */
public final class CliSessionPointer {
    private final Path homeRoot;

    /**
     * 固定指针文件所在的家目录根。
     *
     * @param homeRoot 用户家目录的父根，会先规范成绝对路径
     */
    public CliSessionPointer(Path homeRoot) {
        this.homeRoot = homeRoot.toAbsolutePath().normalize();
    }

    /**
     * 定位命令行会话指针文件。
     *
     * @return {@code 家目录/.jasagent/cli-session}
     */
    public Path file() {
        return homeRoot.resolve(".jasagent").resolve("cli-session");
    }

    /**
     * 把登录名和令牌写成仅属主可读写的指针文件，供后续本机命令识别当前用户。
     *
     * @param session 刚刚建立、需要被命令行记住的会话
     * @return 指针文件写完后完成的阶段
     */
    public CompletionStage<Void> remember(Session session) {
        return VirtualIo.supply(() -> {
            String body = "login=" + session.login() + "\ntoken=" + session.token() + "\n";
            OwnerFiles.write(file(), body);
            return null;
        });
    }

    /**
     * 删除命令行会话指针，使本机命令不再沿用先前的登录。
     *
     * @return 指针文件被删除或不存在时完成的阶段
     */
    public CompletionStage<Void> clear() {
        return VirtualIo.supply(() -> {
            Files.deleteIfExists(file());
            return null;
        });
    }

    /**
     * 从指针文件读出当前命令行登录名；文件不存在或没有登录行时得到空串。
     *
     * @return 完成后得到登录名，没有指针时为空串
     */
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
