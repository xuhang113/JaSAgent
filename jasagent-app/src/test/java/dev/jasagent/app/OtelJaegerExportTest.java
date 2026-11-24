package dev.jasagent.app;

import com.sun.net.httpserver.HttpServer;
import dev.jasagent.agentpass.PassSettings;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.LocalAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端点未配置时不外发。端点是地址时，一次不经过 Funnel 的真实执行会把根 span 交到 OTLP HTTP 接收端。
 */
@Timeout(60)
class OtelJaegerExportTest {
    /**
     * 根 span 名称在 OTLP protobuf 里的字段标记。名称字段号是 5，长度正好是 {@code jasagent.execution}。
     */
    private static final byte[] ROOT_SPAN = spanName("jasagent.execution");

    /**
     * 没有端点、空白端点都不会打到接收端。内存里的 trace 仍是 32 位且没有横线。
     * 副作用：起一个本机接收端，并各跑一次只有结束节点的执行。
     */
    @Test
    void unsetOrBlankEndpointDoesNotExport() throws Exception {
        Receiver receiver = Receiver.start();
        try {
            Run unset = run(Map.of(PassSettings.ENABLED, "true"), receiver);
            assertEquals(0, receiver.bodies().size(), unset.detail());
            assertEquals(32, unset.traceId().length(), unset.detail());
            assertFalse(unset.traceId().contains("-"), unset.detail());
            int before = receiver.bodies().size();
            Run blank = run(Map.of(PassSettings.ENABLED, "true", OtlpWiredTracer.ENDPOINT, "   "), receiver);
            assertEquals(before, receiver.bodies().size(), blank.detail());
            assertEquals(32, blank.traceId().length(), blank.detail());
            assertFalse(blank.traceId().contains("-"), blank.detail());
        } finally {
            receiver.close();
        }
    }

    /**
     * 配了端点后，真实执行的根 span 会被接收端收到，正文里没有另外放进配置的标记。
     * 副作用：起接收端并跑一次只有结束节点的执行，然后关闭运行时以刷出批量导出。
     */
    @Test
    void configuredEndpointExportsExecutionRootSpan() throws Exception {
        Receiver receiver = Receiver.start();
        String marker = "tok-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put(PassSettings.ENABLED, "true");
        configuration.put(OtlpWiredTracer.ENDPOINT, receiver.endpoint());
        configuration.put("jasagent.otel.marker", marker);
        try {
            Run run = run(configuration, receiver);
            assertEquals("SUCCEEDED", run.status(), run.detail());
            assertEquals(32, run.traceId().length(), run.detail());
            assertFalse(run.traceId().contains("-"), run.detail());
            assertFalse(receiver.bodies().isEmpty(), run.detail());
            boolean named = false;
            for (byte[] body : receiver.bodies()) {
                assertFalse(contains(body, marker), "导出正文含有未交给导出器的标记");
                assertFalse(contains(body, "Authorization"), "导出正文含有鉴权头");
                assertFalse(contains(body, "Cookie"), "导出正文含有 Cookie");
                if (indexOf(body, ROOT_SPAN) >= 0) {
                    named = true;
                }
            }
            assertTrue(named, run.detail());
            for (List<String> names : receiver.headerNames()) {
                for (String name : names) {
                    assertFalse("authorization".equalsIgnoreCase(name), "导出请求带了鉴权头");
                    assertFalse("cookie".equalsIgnoreCase(name), "导出请求带了 Cookie");
                }
            }
        } finally {
            receiver.close();
        }
    }

    /**
     * 准备用户、工作区和只有结束节点的工作流，提交一次执行并等到终态，再关闭运行时。
     * 副作用：写临时目录并启动运行时。
     *
     * @param configuration 运行配置
     * @param receiver 只为测试记下的接收端，不参与业务
     * @return 终态和内存里的 trace
     */
    private static Run run(Map<String, String> configuration, Receiver receiver) throws Exception {
        Path home = Files.createTempDirectory("jasagent-otel-home");
        Path workspace = Files.createTempDirectory("jasagent-otel-ws");
        Path file = workspace.resolve("jasagent").resolve("workflows").resolve("ping.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"id":"ping","nodes":[{"id":"done","type":"end"}]}
                """);
        LocalAuthService auth = new LocalAuthService(home);
        String login = "otel-" + UUID.randomUUID().toString().substring(0, 8);
        char[] password = ("pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)).toCharArray();
        auth.addUser(new AddUser(login, password, null, false)).toCompletableFuture().join();
        Session session = auth.login(login, password).toCompletableFuture().join();
        auth.selectWorkspace(session.token(), workspace, List.of(workspace), workspace).toCompletableFuture().join();
        LocalRuntime runtime = new LocalRuntime(auth, configuration);
        try {
            Map<String, Object> created = runtime.submit(login, session.token(), "ping", "hello");
            String executionId = String.valueOf(created.get("executionId"));
            Map<String, Object> detail = awaitTerminal(runtime, login, executionId);
            return new Run(String.valueOf(detail.get("status")), String.valueOf(detail.get("errorCode")), String.valueOf(detail.get("traceId")));
        } finally {
            runtime.close();
        }
    }

    /**
     * 轮询直到成功、失败、取消或挂起。超时仍返回最后一次详情。
     *
     * @param runtime 被观察的运行时
     * @param userId 所有者
     * @param executionId 执行编号
     * @return 终态或最后一次详情
     */
    private static Map<String, Object> awaitTerminal(LocalRuntime runtime, String userId, String executionId) throws Exception {
        Map<String, Object> detail = Map.of();
        for (int attempt = 0; attempt < 200; attempt++) {
            detail = runtime.detail(userId, executionId);
            String status = String.valueOf(detail.get("status"));
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status) || "SUSPENDED".equals(status)) {
                return detail;
            }
            Thread.sleep(50);
        }
        return detail;
    }

    /**
     * 拼出 span 名称在 protobuf 里的长度前缀，避免只匹配属性名的前半段。
     *
     * @param name span 名称
     * @return 字段标记加名称
     */
    private static byte[] spanName(String name) {
        byte[] text = name.getBytes(StandardCharsets.UTF_8);
        byte[] marked = new byte[text.length + 2];
        marked[0] = 0x2a;
        marked[1] = (byte) text.length;
        System.arraycopy(text, 0, marked, 2, text.length);
        return marked;
    }

    /**
     * 正文里是否含有这段 UTF-8。
     *
     * @param body 导出正文
     * @param text 要找的文本
     * @return 含有时为真
     */
    private static boolean contains(byte[] body, String text) {
        return indexOf(body, text.getBytes(StandardCharsets.UTF_8)) >= 0;
    }

    /**
     * 在字节里找一段连续内容。
     *
     * @param body 被搜索的正文
     * @param needle 要找的字节
     * @return 起点下标；没有时为 -1
     */
    private static int indexOf(byte[] body, byte[] needle) {
        if (body == null || needle.length == 0 || body.length < needle.length) {
            return -1;
        }
        for (int i = 0; i <= body.length - needle.length; i++) {
            boolean same = true;
            for (int j = 0; j < needle.length; j++) {
                if (body[i + j] != needle[j]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 一次执行的终态。错误码只用于失败时的说明，不含口令。
     *
     * @param status 状态名
     * @param errorCode 错误码
     * @param traceId 内存追踪记下的 trace
     */
    private record Run(String status, String errorCode, String traceId) {
        /**
         * 给断言用的短说明。不含口令、令牌或标记。
         *
         * @return 状态和错误码
         */
        private String detail() {
            return status + " " + errorCode;
        }
    }

    /**
     * 本机 OTLP HTTP 接收端。只保存正文和请求头名字，不保存头的值。
     */
    private static final class Receiver implements AutoCloseable {
        private final HttpServer server;
        private final List<byte[]> bodies = new CopyOnWriteArrayList<>();
        private final List<List<String>> headerNames = new CopyOnWriteArrayList<>();

        /**
         * 记下已经听上的服务。
         *
         * @param server 本机服务
         */
        private Receiver(HttpServer server) {
            this.server = server;
        }

        /**
         * 在回环的空闲端口上接收 POST。任何路径都收，并回 200。
         *
         * @return 已启动的接收端
         */
        static Receiver start() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            Receiver receiver = new Receiver(server);
            server.createContext("/", exchange -> {
                receiver.bodies.add(exchange.getRequestBody().readAllBytes());
                receiver.headerNames.add(new ArrayList<>(exchange.getRequestHeaders().keySet()));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return receiver;
        }

        /**
         * 导出器要使用的完整地址。
         *
         * @return 含 /v1/traces 的本机地址
         */
        String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces";
        }

        /**
         * 已经收到的正文。
         *
         * @return 不可变副本
         */
        List<byte[]> bodies() {
            return List.copyOf(bodies);
        }

        /**
         * 每次请求的头名字。
         *
         * @return 不可变副本
         */
        List<List<String>> headerNames() {
            return List.copyOf(headerNames);
        }

        /**
         * 停止接收。
         */
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
