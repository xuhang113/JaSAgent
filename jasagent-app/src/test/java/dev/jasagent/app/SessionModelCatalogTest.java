package dev.jasagent.app;

import com.sun.net.httpserver.HttpServer;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.llm.LlmSettings;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住模型目录的排列、缺密钥、Gateway 不可达或重名，以及本机桩上的列表解析和端口探测。
 */
class SessionModelCatalogTest {
    /**
     * 测试用的占位 DeepSeek 密钥，不是真实密钥，只用来证明请求会带上 Bearer。
     */
    private static final String KEY = "placeholder-deepseek-key";

    /**
     * 目录返回 v4-pro 和 flash 时，可选项仍是 flash 在前。按 deepseek 供应商可以取到 v4-pro。
     * 副作用：无外部写入。断言失败则测试失败。
     */
    @Test
    void deepSeekModelsComeFromTheEndpointAndDefaultStaysFirst() {
        SessionModelCatalog catalog = catalog(KEY, "", "", "", List.of("deepseek-v4-pro", "deepseek-flash"), true);
        List<SessionModelCatalog.ModelChoice> options = catalog.available();
        assertEquals(List.of("deepseek-flash", "deepseek-v4-pro"), options.stream().map(SessionModelCatalog.ModelChoice::id).toList());
        assertEquals(List.of("deepseek", "deepseek"), options.stream().map(SessionModelCatalog.ModelChoice::provider).toList());
        assertEquals("deepseek-v4-pro", catalog.require("deepseek-v4-pro", "deepseek").id());
    }

    /**
     * 密钥为空时不调用模型列表，可选项为空，再按 deepseek 取 flash 会得到 WORKFLOW。
     * 副作用：无外部写入。断言失败则测试失败。
     */
    @Test
    void missingDeepSeekKeyDoesNotListModels() {
        AtomicInteger calls = new AtomicInteger();
        SessionModelCatalog catalog = new SessionModelCatalog(
                settings("", "", "", ""),
                key -> {
                    calls.incrementAndGet();
                    return List.of("deepseek-flash");
                },
                url -> true);
        assertTrue(catalog.available().isEmpty());
        assertEquals(0, calls.get());
        AgentException error = assertThrows(AgentException.class, () -> catalog.require("deepseek-flash", "deepseek"));
        assertEquals(ErrorCode.WORKFLOW, error.code());
    }

    /**
     * Gateway 缺密钥，或探针说不可达时，不列出 vendor-model。flash 仍可在不指定供应商时取到；按 ai-gateway 取 vendor-model 得到 WORKFLOW。
     * 副作用：无外部写入。断言失败则测试失败。
     */
    @Test
    void unconfiguredOrUnreachableGatewayIsHidden() {
        SessionModelCatalog missing = catalog(KEY, "https://gateway.example", "", "vendor-model", List.of("deepseek-flash"), true);
        assertFalse(ids(missing).contains("vendor-model"));
        SessionModelCatalog down = catalog(KEY, "https://gateway.example", "placeholder-gateway-key", "vendor-model", List.of("deepseek-flash"), false);
        assertFalse(ids(down).contains("vendor-model"));
        assertEquals("deepseek-flash", down.require("deepseek-flash", null).id());
        AgentException error = assertThrows(AgentException.class, () -> down.require("vendor-model", "ai-gateway"));
        assertEquals(ErrorCode.WORKFLOW, error.code());
    }

    /**
     * Gateway 配置齐全且探针可达时，可以按 ai-gateway 取到 vendor-model，展示名就是模型 id。
     * 副作用：无外部写入。断言失败则测试失败。
     */
    @Test
    void reachableGatewayIsListed() {
        SessionModelCatalog catalog = catalog(
                KEY,
                "https://gateway.example",
                "placeholder-gateway-key",
                "vendor-model",
                List.of("deepseek-flash"),
                true);
        SessionModelCatalog.ModelChoice gateway = catalog.require("vendor-model", "ai-gateway");
        assertEquals("ai-gateway", gateway.provider());
        assertEquals("vendor-model", gateway.label());
    }

    /**
     * Gateway 的模型 id 也是 deepseek-flash 时，Gateway 那条的展示名带 Gateway 后缀，DeepSeek 那条的供应商仍是 deepseek。
     * 副作用：无外部写入。断言失败则测试失败。
     */
    @Test
    void collidingGatewayNameIsLabeled() {
        SessionModelCatalog catalog = catalog(
                KEY,
                "https://gateway.example",
                "placeholder-gateway-key",
                "deepseek-flash",
                List.of("deepseek-flash"),
                true);
        assertEquals("deepseek-flash（Gateway）", catalog.require("deepseek-flash", "ai-gateway").label());
        assertEquals("deepseek", catalog.require("deepseek-flash", "deepseek").provider());
    }

    /**
     * 本机 /models 返回带空格的 id。空白密钥得到空列表且不发请求；有效密钥得到去掉空白的 id，Authorization 以 Bearer 开头，结果里不含密钥。默认地址是 DeepSeek 的 /models。
     * 副作用：启动并停止本机 HTTP 桩。断言失败则测试失败。
     */
    @Test
    void directoryReadsModelIdsFromDeepSeek() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"data":[{"id":"deepseek-flash"},{"id":" deepseek-v4-pro "}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            DeepSeekModelDirectory directory = new DeepSeekModelDirectory(java.net.URI.create("http://127.0.0.1:" + port + "/models"));
            assertEquals(List.of(), directory.ids("  "));
            List<String> ids = directory.ids(KEY);
            assertEquals(List.of("deepseek-flash", "deepseek-v4-pro"), ids);
            assertTrue(authorization.get().startsWith("Bearer "));
            assertFalse(String.join(",", ids).contains(KEY));
            assertEquals("https://api.deepseek.com/models", DeepSeekModelDirectory.MODELS.toString());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 返回 204 的本机端口算可达。刚释放的端口、空串和不是 URL 的文字都不可达。
     * 副作用：启动并停止本机 HTTP 桩，短暂占用一个端口。断言失败则测试失败。
     */
    @Test
    void gatewayProbeFollowsAnAnswerAndRejectsAClosedPort() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        try {
            GatewayReachability probe = new GatewayReachability();
            assertTrue(probe.reachable("http://127.0.0.1:" + server.getAddress().getPort()));
            assertFalse(probe.reachable("http://127.0.0.1:" + closed));
            assertFalse(probe.reachable(""));
            assertFalse(probe.reachable("not a url"));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 把目录当前可选项收成模型 id 列表，方便断言有没有出现某个模型。
     *
     * @param catalog 被查看的目录
     * @return 按展示顺序排列的 id
     */
    private static List<String> ids(SessionModelCatalog catalog) {
        return catalog.available().stream().map(SessionModelCatalog.ModelChoice::id).toList();
    }

    /**
     * 用给定密钥、Gateway 设置、固定的 DeepSeek id 和可达性组装目录，不访问真实网络。
     *
     * @param deepseekKey DeepSeek 密钥，空串表示没配置
     * @param gatewayBase Gateway 基址
     * @param gatewayKey Gateway 密钥
     * @param gatewayModel Gateway 模型 id
     * @param deepseekIds 目录直接返回的 DeepSeek id
     * @param gatewayUp 探针是否报告基址可达
     * @return 测试用目录
     */
    private static SessionModelCatalog catalog(
            String deepseekKey,
            String gatewayBase,
            String gatewayKey,
            String gatewayModel,
            List<String> deepseekIds,
            boolean gatewayUp) {
        return new SessionModelCatalog(settings(deepseekKey, gatewayBase, gatewayKey, gatewayModel), key -> deepseekIds, url -> gatewayUp);
    }

    /**
     * 组装关闭思考和向量、超时 60 秒的模型设置。
     *
     * @param deepseekKey DeepSeek 密钥
     * @param gatewayBase Gateway 基址
     * @param gatewayKey Gateway 密钥
     * @param gatewayModel Gateway 模型 id
     * @return 供目录读取的设置
     */
    private static LlmSettings settings(String deepseekKey, String gatewayBase, String gatewayKey, String gatewayModel) {
        return new LlmSettings(deepseekKey, false, gatewayBase, gatewayKey, gatewayModel, false, "", Duration.ofSeconds(60));
    }
}
