package dev.jasagent.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确认启动脚本怎样把 llgraph.env 映射成 JASAGENT 的模型变量：注释和残缺的 Gateway 段不能被发明出来，进程里已有的值不能被盖掉。
 */
class ModelEnvScriptTest {
    /**
     * 夹具里的占位 DeepSeek 密钥，用来核对映射结果，不是真实密钥。
     */
    private static final String DEEPSEEK = "placeholder-deepseek-key";
    /**
     * 夹具里的占位 Gateway 密钥，用来核对活动段和被注释段，不是真实密钥。
     */
    private static final String GATEWAY = "placeholder-gateway-key";
    /**
     * 进程里预先放好的占位值，用来证明加载脚本不会覆盖已经存在的 JASAGENT 变量。
     */
    private static final String ALREADY = "already-set-placeholder";

    /**
     * 被注释掉的 Gateway 段不能产生基址、模型或密钥。Deepseek 段仍映射成 JASAGENT_DEEPSEEK_API_KEY，脚本标准输出和错误都为空。
     * 副作用：写临时 env 文件并启动 bash。断言失败则测试失败。
     */
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

    /**
     * 活动 Gateway 段去掉引号后映射基址、模型和密钥，Deepseek 密钥单独映射。脚本不能往标准输出或错误写字。
     * 副作用：写临时 env 文件并启动 bash。断言失败则测试失败。
     */
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

    /**
     * Gateway 段只有基址和模型、没有密钥时，三个 Gateway 变量都不能出现。Deepseek 密钥仍然映射。
     * 副作用：写临时 env 文件并启动 bash。断言失败则测试失败。
     */
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

    /**
     * 进程里已经有 DeepSeek 和 Gateway 变量时，env 文件里的另一把密钥不能覆盖它们。
     * 副作用：写临时 env 文件并启动 bash。断言失败则测试失败。
     */
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

    /**
     * start.sh 必须在 nohup 之前调用加载函数，jasagent 启动脚本必须在 exec 之前调用，避免 Java 先起来才读到模型环境。
     * 副作用：只读仓库里的两个脚本。断言失败则测试失败。
     */
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

    /**
     * 加载脚本的标准输出和标准错误都必须是空的，否则抛出断言错误。
     * 副作用：输出非空时让测试失败。
     *
     * @param loaded 一次加载的输出和变量
     */
    private static void assertSilent(Loaded loaded) {
        if (!loaded.stdout.isEmpty() || !loaded.stderr.isEmpty()) {
            throw new AssertionError("加载脚本写出了 " + loaded.stdout.length() + " 字节标准输出和 " + loaded.stderr.length() + " 字节标准错误");
        }
    }

    /**
     * 读取加载后报告里的变量。变量根本没出现时抛出断言错误，而不是返回空串。
     *
     * @param loaded 一次加载的结果
     * @param name JASAGENT 变量名
     * @return 变量值
     */
    private static String value(Loaded loaded, String name) {
        if (!loaded.values.containsKey(name)) {
            throw new AssertionError("进程环境没有 " + name);
        }
        return loaded.values.get(name);
    }

    /**
     * 把一段 env 原文写到临时目录的 llgraph.env。原文保持原样，不在正文里插注释。
     * 副作用：创建临时目录和文件。
     *
     * @param body env 文件全文
     * @return 写出的文件
     */
    private static Path fixture(String body) throws Exception {
        Path file = Files.createTempDirectory("jasagent-llgraph-env").resolve("llgraph.env");
        Files.writeString(file, body);
        return file;
    }

    /**
     * 在清空过的环境里用 bash 加载模型环境。preset 先导出，脚本再读取 env 文件，并把关心的变量写进报告。
     * 副作用：创建临时文件和空的 HOME，并启动 bash。脚本非 0 时抛出断言错误。
     *
     * @param envFile 要加载的 llgraph.env
     * @param preset 进程里已经存在的变量
     * @return 标准输出、标准错误和报告中的变量
     */
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

    /**
     * 定位含 scripts/common.sh 的仓库根。当前目录不是时再看上一级。
     *
     * @return 仓库根目录
     */
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

    /**
     * 一次模型环境加载的结果。
     *
     * @param stdout 加载函数的标准输出
     * @param stderr 加载函数的标准错误
     * @param values 报告里出现的 JASAGENT 变量
     */
    private record Loaded(String stdout, String stderr, Map<String, String> values) {
    }
}
