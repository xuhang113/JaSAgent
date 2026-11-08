package dev.jasagent.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelEnvScriptTest {
    private static final String DEEPSEEK = "placeholder-deepseek-key";
    private static final String GATEWAY = "placeholder-gateway-key";
    private static final String ALREADY = "already-set-placeholder";

    @Test
    void commentedGatewayIsNotInvented() throws Exception {
        Path env = fixture("""
                # ai gateway
                # LLGRAPH_API_BASE_URL=https://gateway.example
                # LLGRAPH_API_KEY=%s
                # LLGRAPH_MODEL=commented-model

                Deepseek
                LLGRAPH_API_BASE_URL=https://api.deepseek.com/anthropic
                LLGRAPH_API_KEY=%s
                LLGRAPH_MODEL=deepseek-v4-pro
                """.formatted(GATEWAY, DEEPSEEK));
        Loaded loaded = load(env, Map.of());
        assertSilent(loaded);
        assertEquals(DEEPSEEK, value(loaded, "JASAGENT_DEEPSEEK_API_KEY"));
        assertFalse(loaded.values.containsKey("JASAGENT_GATEWAY_BASE_URL"), loaded.values.keySet().toString());
        assertFalse(loaded.values.containsKey("JASAGENT_GATEWAY_MODEL"), loaded.values.keySet().toString());
        assertFalse(loaded.values.containsKey("JASAGENT_GATEWAY_API_KEY"), loaded.values.keySet().toString());
    }

    @Test
    void activeGatewaySectionIsMapped() throws Exception {
        Path env = fixture("""
                Gateway
                LLGRAPH_API_BASE_URL=https://gateway.example
                LLGRAPH_API_KEY="%s"
                LLGRAPH_MODEL=vendor-model

                Deepseek
                LLGRAPH_API_KEY=%s
                LLGRAPH_MODEL=deepseek-v4-pro
                """.formatted(GATEWAY, DEEPSEEK));
        Loaded loaded = load(env, Map.of());
        assertSilent(loaded);
        assertEquals(DEEPSEEK, value(loaded, "JASAGENT_DEEPSEEK_API_KEY"));
        assertEquals("https://gateway.example", value(loaded, "JASAGENT_GATEWAY_BASE_URL"));
        assertEquals("vendor-model", value(loaded, "JASAGENT_GATEWAY_MODEL"));
        assertEquals(GATEWAY, value(loaded, "JASAGENT_GATEWAY_API_KEY"));
    }

    @Test
    void incompleteGatewayIsNotInvented() throws Exception {
        Path env = fixture("""
                AI Gateway
                LLGRAPH_API_BASE_URL=https://gateway.example
                LLGRAPH_MODEL=vendor-model

                Deepseek
                LLGRAPH_API_KEY=%s
                """.formatted(DEEPSEEK));
        Loaded loaded = load(env, Map.of());
        assertSilent(loaded);
        assertEquals(DEEPSEEK, value(loaded, "JASAGENT_DEEPSEEK_API_KEY"));
        assertFalse(loaded.values.containsKey("JASAGENT_GATEWAY_BASE_URL"));
        assertFalse(loaded.values.containsKey("JASAGENT_GATEWAY_MODEL"));
        assertFalse(loaded.values.containsKey("JASAGENT_GATEWAY_API_KEY"));
    }

    @Test
    void existingProcessKeyIsKept() throws Exception {
        Path env = fixture("LLGRAPH_API_KEY=" + DEEPSEEK + "\n");
        Loaded loaded = load(env, Map.of(
                "JASAGENT_DEEPSEEK_API_KEY", ALREADY,
                "JASAGENT_GATEWAY_BASE_URL", "https://already.example",
                "JASAGENT_GATEWAY_MODEL", "already-model",
                "JASAGENT_GATEWAY_API_KEY", ALREADY));
        assertSilent(loaded);
        assertEquals(ALREADY, value(loaded, "JASAGENT_DEEPSEEK_API_KEY"));
        assertEquals("https://already.example", value(loaded, "JASAGENT_GATEWAY_BASE_URL"));
        assertEquals("already-model", value(loaded, "JASAGENT_GATEWAY_MODEL"));
        assertEquals(ALREADY, value(loaded, "JASAGENT_GATEWAY_API_KEY"));
    }

    @Test
    void startupScriptsLoadModelEnvBeforeJava() throws Exception {
        Path root = repoRoot();
        String start = Files.readString(root.resolve("scripts").resolve("start.sh"));
        String launcher = Files.readString(root.resolve("jasagent"));
        int load = start.indexOf("jasagent_load_model_env");
        int launch = start.indexOf("nohup");
        assertTrue(load >= 0 && launch > load, "start.sh 没有在启动 Java 前加载模型环境");
        int cliLoad = launcher.indexOf("jasagent_load_model_env");
        int exec = launcher.indexOf("\nexec ");
        assertTrue(cliLoad >= 0 && exec > cliLoad, "jasagent 没有在执行 Java 前加载模型环境");
    }

    private static void assertSilent(Loaded loaded) {
        if (!loaded.stdout.isEmpty() || !loaded.stderr.isEmpty()) {
            throw new AssertionError("加载脚本写出了 " + loaded.stdout.length() + " 字节标准输出和 " + loaded.stderr.length() + " 字节标准错误");
        }
    }

    private static String value(Loaded loaded, String name) {
        if (!loaded.values.containsKey(name)) {
            throw new AssertionError("进程环境没有 " + name);
        }
        return loaded.values.get(name);
    }

    private static Path fixture(String body) throws Exception {
        Path file = Files.createTempDirectory("jasagent-llgraph-env").resolve("llgraph.env");
        Files.writeString(file, body);
        return file;
    }

    private static Loaded load(Path envFile, Map<String, String> preset) throws Exception {
        Path root = repoRoot();
        Path out = Files.createTempFile("jasagent-env-out", ".txt");
        Path err = Files.createTempFile("jasagent-env-err", ".txt");
        Path report = Files.createTempFile("jasagent-env-report", ".txt");
        StringBuilder exports = new StringBuilder();
        for (Map.Entry<String, String> entry : preset.entrySet()) {
            exports.append("export ").append(entry.getKey()).append("='").append(entry.getValue().replace("'", "'\\''")).append("'\n");
        }
        String script = """
                set -euo pipefail
                source "$ROOT/scripts/common.sh"
                %s
                jasagent_load_model_env >"$OUT" 2>"$ERR"
                {
                  printf 'JASAGENT_DEEPSEEK_API_KEY=%%s\\n' "${JASAGENT_DEEPSEEK_API_KEY-}"
                  if [[ -n "${JASAGENT_GATEWAY_BASE_URL+x}" ]]; then printf 'JASAGENT_GATEWAY_BASE_URL=%%s\\n' "$JASAGENT_GATEWAY_BASE_URL"; fi
                  if [[ -n "${JASAGENT_GATEWAY_MODEL+x}" ]]; then printf 'JASAGENT_GATEWAY_MODEL=%%s\\n' "$JASAGENT_GATEWAY_MODEL"; fi
                  if [[ -n "${JASAGENT_GATEWAY_API_KEY+x}" ]]; then printf 'JASAGENT_GATEWAY_API_KEY=%%s\\n' "$JASAGENT_GATEWAY_API_KEY"; fi
                } >"$REPORT"
                """.formatted(exports);
        ProcessBuilder builder = new ProcessBuilder("bash", "--noprofile", "--norc", "-c", script);
        builder.environment().clear();
        builder.environment().put("PATH", System.getenv("PATH"));
        builder.environment().put("HOME", Files.createTempDirectory("jasagent-empty-home").toString());
        builder.environment().put("ROOT", root.toString());
        builder.environment().put("OUT", out.toString());
        builder.environment().put("ERR", err.toString());
        builder.environment().put("REPORT", report.toString());
        builder.environment().put("JASAGENT_LLGRAPH_ENV", envFile.toString());
        Process process = builder.start();
        String processOut = new String(process.getInputStream().readAllBytes());
        String processErr = new String(process.getErrorStream().readAllBytes());
        int code = process.waitFor();
        if (code != 0) {
            throw new AssertionError("加载脚本退出 " + code + "，标准输出 " + processOut.length() + " 字节，标准错误 " + processErr.length() + " 字节");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readString(report).split("\\R")) {
            int split = line.indexOf('=');
            if (split < 0) {
                continue;
            }
            values.put(line.substring(0, split), line.substring(split + 1));
        }
        return new Loaded(Files.readString(out), Files.readString(err), values);
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(current.resolve("scripts").resolve("common.sh"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("scripts").resolve("common.sh"))) {
            return parent;
        }
        throw new AssertionError("找不到 scripts/common.sh");
    }

    private record Loaded(String stdout, String stderr, Map<String, String> values) {
    }
}
