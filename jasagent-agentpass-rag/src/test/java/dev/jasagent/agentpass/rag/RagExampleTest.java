package dev.jasagent.agentpass.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.agentpass.server.AgentPassServerApplication;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.LocalAuthService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 示例检索接上 starter 之后：无证和重复使用被拒绝，有效证能返回检索结果。
 * 领证走 {@code POST /v1/tickets}，证放在 {@code X-Jasagent-Ticket}。口令只在内存里生成。
 */
class RagExampleTest {
    private static final String TARGET = "http://127.0.0.1:18751";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static Path home;
    private static ConfigurableApplicationContext server;
    private static ConfigurableApplicationContext rag;
    private static int serverPort;
    private static int ragPort;
    private static Session alice;

    /**
     * 拉起通行证服务和示例检索。检索即使监听随机端口，过滤器对照的目标仍是 18751 这个配置值。
     */
    @BeforeAll
    static void boot() throws Exception {
        home = Files.createTempDirectory("jasagent-agentpass-rag-");
        alice = register("alice");
        Files.writeString(home.resolve("alice").resolve("jasagent").resolve("auth").resolve("policies.yaml"), """
                rules:
                  - tool: rag.search
                    decision: ALLOW
                """);
        server = SpringApplication.run(AgentPassServerApplication.class,
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.main.banner-mode=off",
                "--jasagent.home=" + home);
        serverPort = Integer.parseInt(server.getEnvironment().getProperty("local.server.port"));
        rag = SpringApplication.run(RagExampleApplication.class,
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.main.banner-mode=off",
                "--jasagent.agentpass.client-base-url=http://127.0.0.1:" + serverPort,
                "--jasagent.agentpass.service-name=" + TARGET,
                "--jasagent.agentpass.tool-name=rag.search",
                "--jasagent.agentpass.tenant=local",
                "--jasagent.agentpass.agent-path-prefix=/search");
        ragPort = Integer.parseInt(rag.getEnvironment().getProperty("local.server.port"));
    }

    /**
     * 停掉两个进程并删掉带口令摘要的临时目录。
     */
    @AfterAll
    static void shutdown() throws Exception {
        if (rag != null) {
            rag.close();
        }
        if (server != null) {
            server.close();
        }
        if (home != null && Files.exists(home)) {
            try (Stream<Path> walk = Files.walk(home)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    /**
     * 健康检查不带证也能打开，说明普通豁免流量没有被过滤器挡住。
     */
    @Test
    void healthDoesNotNeedTicket() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ragPort + "/health"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        int before = rag.getBean(SearchLog.class).queries().size();
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("rag"));
        assertEquals(before, rag.getBean(SearchLog.class).queries().size());
    }

    /**
     * 没有证时检索被拒绝，业务没有执行。
     */
    @Test
    void missingTicketIsRejected() throws Exception {
        int before = rag.getBean(SearchLog.class).queries().size();
        HttpResponse<String> response = postSearch(null);
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("missing-pass"));
        assertFalse(response.body().contains("一次性通行证"));
        assertEquals(before, rag.getBean(SearchLog.class).queries().size());
    }

    /**
     * 有效证返回命中；同一张证再用一次被拒绝，检索只执行了一次。
     */
    @Test
    void validTicketReturnsHitsAndReuseIsRejected() throws Exception {
        String ticket = issueTicket();
        assertFalse(ticket.isBlank());
        int before = rag.getBean(SearchLog.class).queries().size();
        HttpResponse<String> first = postSearch(ticket);
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.body().contains("一次性通行证"));
        assertTrue(first.body().contains("agentpass.md"));
        assertEquals(before + 1, rag.getBean(SearchLog.class).queries().size());
        HttpResponse<String> second = postSearch(ticket);
        assertEquals(403, second.statusCode(), second.body());
        assertTrue(second.body().contains("\"reason\":\"used\""));
        assertEquals(before + 1, rag.getBean(SearchLog.class).queries().size());
    }

    /**
     * 按运行时的字段向 {@code POST /v1/tickets} 领证，并确认响应里的 ticket 不含登录令牌。
     *
     * @return 证明文
     */
    private static String issueTicket() throws Exception {
        String body = JSON.writeValueAsString(java.util.Map.of(
                "tenantId", "local",
                "userId", "alice",
                "sessionId", alice.sessionId(),
                "executionId", "exec-rag",
                "target", TARGET,
                "toolName", "rag.search"));
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/v1/tickets"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        assertFalse(response.body().contains(alice.token()));
        JsonNode json = JSON.readTree(response.body());
        return json.get("ticket").asText();
    }

    /**
     * 按运行时的方式调用 {@code POST /search}。证为空时不带头。
     *
     * @param ticket 一次性证；没有证时为空
     * @return 检索响应
     */
    private static HttpResponse<String> postSearch(String ticket) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ragPort + "/search"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"一次性通行证\"}"));
        if (ticket != null) {
            builder.header("X-Jasagent-Ticket", ticket);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 创建用户并登录。口令用完即清，不写入日志。
     *
     * @param login 登录名
     * @return 已登录会话
     */
    private static Session register(String login) {
        LocalAuthService auth = new LocalAuthService(home);
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        char[] password = HexFormat.of().formatHex(bytes).toCharArray();
        try {
            auth.addUser(new AddUser(login, password, null, true)).toCompletableFuture().join();
            return auth.login(login, password).toCompletableFuture().join();
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
