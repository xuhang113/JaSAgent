package dev.jasagent.app;

import dev.jasagent.cli.CliCommands;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliCommandTest {
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
