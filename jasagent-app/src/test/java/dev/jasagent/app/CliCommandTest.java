package dev.jasagent.app;

import dev.jasagent.cli.CliCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliCommandTest {
    @TempDir
    Path home;

    @Test
    void cliUserAddWritesHash() throws Exception {
        int code = CliCommands.run(new String[]{"user", "add", "--user", "cliuser", "--password", "test-password-1"}, home);
        assertEquals(0, code);
        assertTrue(Files.isRegularFile(home.resolve("cliuser").resolve("jasagent").resolve("auth").resolve("password.hash")));
    }
}
