package dev.jasagent.app;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPortTest {
    @Test
    void applicationPropertiesUse18721() throws Exception {
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        assertTrue(props.contains("server.address=127.0.0.1"), props);
        assertTrue(props.contains("server.port=18721"), props);
        assertFalse(props.contains("server.port=8090"), props);
    }

    @Test
    void applicationMainDefaultsTo18721() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/jasagent/app/JasagentApplication.java"));
        assertTrue(source.contains("\"server.address\", \"127.0.0.1\""), source);
        assertTrue(source.contains("\"server.port\", \"18721\""), source);
    }

    @Test
    void cliFallsBackTo18721() throws Exception {
        Path home = Files.createTempDirectory("jasagent-port-home");
        assertFalse(Files.exists(home.resolve(".jasagent").resolve("runtime").resolve("address")));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder builder = new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "dev.jasagent.app.CliBaseUrlProbe",
                home.toString());
        builder.environment().remove("JASAGENT_BASE_URL");
        builder.environment().remove("JASAGENT_RUNTIME_DIR");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        assertEquals(0, code, output);
        assertEquals("http://127.0.0.1:18721", output.trim(), output);
    }

    @Test
    void startScriptDefaultsTo18721() throws Exception {
        String script = Files.readString(Path.of("../scripts/start.sh"));
        assertTrue(script.contains("port=\"${JASAGENT_PORT:-18721}\""), script);
    }

    @Test
    void addressFallbackDefaultsTo18721() throws Exception {
        String script = Files.readString(Path.of("../scripts/common.sh"));
        assertTrue(script.contains("line=\"http://127.0.0.1:18721\""), script);
    }
}
