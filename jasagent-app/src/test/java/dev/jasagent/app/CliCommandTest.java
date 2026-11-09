package dev.jasagent.app;

import dev.jasagent.cli.CliCommands;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确认命令行新增用户时把口令写成哈希，而不是把原文留在磁盘上。
 */
class CliCommandTest {
    /**
     * 在临时家目录执行 user add，确认退出码为 0，口令文件存在、内容非空，且不等于刚才输入的口令。
     * 副作用：创建临时家目录和用户的 password.hash。断言失败则测试失败。
     */
    @Test
    void cliUserAddWritesHash() throws Exception {
        Path home = Files.createTempDirectory("jasagent-cli-home");
        String password = "test-password-1";
        int code = CliCommands.run(new String[]{"user", "add", "--user", "first-user", "--password", password}, home);
        assertEquals(0, code);
        Path hash = home.resolve("first-user").resolve("jasagent").resolve("auth").resolve("password.hash");
        assertTrue(Files.isRegularFile(hash), hash.toString());
        String stored = Files.readString(hash).trim();
        assertFalse(stored.isBlank());
        assertNotEquals(password, stored);
    }
}
