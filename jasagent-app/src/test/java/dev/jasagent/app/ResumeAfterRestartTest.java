package dev.jasagent.app;

import dev.jasagent.api.persist.RunSnapshot;
import dev.jasagent.interrupt.InterruptService;
import dev.jasagent.persist.JdbcExecutionStore;
import dev.jasagent.persist.JdbcSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResumeAfterRestartTest {
    @TempDir
    Path temp;

    @Test
    void reopenJdbcThenResume() {
        JdbcSettings settings = JdbcSettings.from(Map.of(), Map.of(), temp.resolve("ws"));
        JdbcExecutionStore first = new JdbcExecutionStore(settings);
        first.save(new RunSnapshot("exec-restart", "SUSPENDED", "find", "aabbccddeeff00112233445566778899", ""));
        first.close();
        JdbcExecutionStore second = new JdbcExecutionStore(settings);
        try {
            InterruptService service = new InterruptService(second);
            RunSnapshot resumed = service.resume("exec-restart", snapshot -> snapshot.withStatus("SUCCEEDED").withOutput("after-restart"));
            assertEquals("exec-restart", resumed.executionId());
            assertEquals("SUCCEEDED", resumed.status());
            assertEquals("SUCCEEDED", second.find("exec-restart").status());
        } finally {
            second.close();
        }
    }
}
