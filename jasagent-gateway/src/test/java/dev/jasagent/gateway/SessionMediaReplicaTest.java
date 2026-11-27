package dev.jasagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.gateway.media.AttachmentSamples;
import dev.jasagent.gateway.store.MysqlAffinityStore;
import dev.jasagent.gateway.store.UragPassword;
import dev.jasagent.persist.SessionLedger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两个网关进程共用已有 jasagent 库。上传进一个进程，从另一个进程取回同一份字节。
 */
class SessionMediaReplicaTest {
    private static final String BOUNDARY = "jasagent026replica";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * 图片、语音和视频从另一个进程取回。别的租户并发读取和删除都被拒绝。
     */
    @Test
    void twoProcessesShareMysqlBytes() throws Exception {
        List<String> logins = new ArrayList<>();
        Started left = null;
        Started right = null;
        try {
            left = start();
            right = start();
            assertNotEquals(left.port, right.port);
            String secret = secret();
            String login = register(left, secret);
            logins.add(login);
            String cookie = login(left, login, secret);
            JsonNode me = JSON.readTree(send(right, "GET", "/api/me", cookie, null).body());
            assertEquals(login, me.get("login").asText());
            String tenant = me.get("tenantId").asText();
            String sessionId = sessionId();
            String now = Instant.now().toString();
            left.context.getBean(SessionLedger.class).save(tenant, sessionId, login, "会话", "m", "p", false, now, now, "{}");
            byte[] image = AttachmentSamples.png();
            byte[] audio = AttachmentSamples.wav();
            byte[] video = AttachmentSamples.videoMp4();
            String imageId = upload(left, cookie, sessionId, "../../holiday.png", "image/svg+xml", image);
            String audioId = upload(left, cookie, sessionId, "clip.wav", "text/plain", audio);
            String videoId = upload(left, cookie, sessionId, "clip.mp4", "video/ogg", video);
            assertStored(tenant, sessionId, login, imageId, image, "holiday");
            assertBytes(right, cookie, sessionId, imageId, image, "image/png");
            assertBytes(right, cookie, sessionId, audioId, audio, "audio/wav");
            assertBytes(right, cookie, sessionId, videoId, video, "video/mp4");
            HttpResponse<byte[]> denied = send(right, "GET",
                    "/api/sessions/" + sessionId + "/attachments/" + imageId + "?token=query-token-026", null, null);
            assertEquals(401, denied.statusCode());
            assertTrue(new String(denied.body(), StandardCharsets.UTF_8).contains("\"errorCode\":\"AUTH\""));
            assertEquals(404, rawStatus(right.port, "/api/sessions/" + sessionId + "/attachments/../messages", cookie));
            assertEquals(200, rawStatus(right.port, "/api/sessions/" + sessionId + "/%61ttachments", cookie));
            assertEquals(200, rawStatus(right.port, "/API/sessions/" + sessionId + "/ATTACHMENTS", cookie));
            String otherSecret = secret();
            String otherLogin = register(right, otherSecret);
            logins.add(otherLogin);
            String otherCookie = login(right, otherLogin, otherSecret);
            JsonNode otherMe = JSON.readTree(send(left, "GET", "/api/me", otherCookie, null).body());
            String otherSession = sessionId();
            right.context.getBean(SessionLedger.class).save(
                    otherMe.get("tenantId").asText(), otherSession, otherLogin, "会话", "m", "p", false, now, now, "{}");
            String otherId = upload(right, otherCookie, otherSession, "b.png", "image/png", AttachmentSamples.png());
            Started gatewayA = left;
            Started gatewayB = right;
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> readOther = pool.submit(() -> status(gatewayA, cookie, otherSession, otherId));
                Future<Integer> readOwn = pool.submit(() -> status(gatewayB, otherCookie, sessionId, imageId));
                assertEquals(403, readOther.get(20, TimeUnit.SECONDS));
                assertEquals(403, readOwn.get(20, TimeUnit.SECONDS));
                Future<Integer> deleteOther = pool.submit(() -> deleteStatus(gatewayA, cookie, otherSession, otherId));
                Future<Integer> deleteOwn = pool.submit(() -> deleteStatus(gatewayB, otherCookie, sessionId, imageId));
                assertEquals(403, deleteOther.get(20, TimeUnit.SECONDS));
                assertEquals(403, deleteOwn.get(20, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
            assertBytes(left, otherCookie, otherSession, otherId, AttachmentSamples.png(), "image/png");
            assertBytes(right, cookie, sessionId, imageId, image, "image/png");
        } finally {
            close(left);
            close(right);
            cleanup(logins);
        }
    }

    /**
     * 起一个只听随机端口的网关。家目录是空的，不迁入真实用户。
     *
     * @return 已启动的进程
     */
    private static Started start() throws Exception {
        Path home = Files.createTempDirectory("gw026-replica");
        SpringApplication application = new SpringApplication(GatewayApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);
        ConfigurableApplicationContext context = application.run(
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--spring.jmx.enabled=false",
                "--jasagent.gateway.links.enabled=false",
                "--jasagent.home=" + home,
                "--jasagent.gateway.web-origin=http://127.0.0.1:9",
                "--jasagent.nacos.server-addr=",
                "--jasagent.gateway.jaeger-origin=",
                "--logging.level.root=WARN");
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return new Started(context, port);
    }

    /**
     * 注册一个用户。失败时只报告状态码。
     *
     * @param gateway 接收注册的进程
     * @param secret 口令
     * @return 登录名
     */
    private String register(Started gateway, String secret) throws Exception {
        String login = "u" + UUID.randomUUID().toString().replace("-", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base(gateway) + "/register"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("user=" + login + "&password=" + secret))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new IllegalStateException("注册没有被接受: " + response.statusCode());
        }
        assertFalse(response.body().contains(secret));
        return login;
    }

    /**
     * 登录并取出 Cookie 值。异常说明里不放 Cookie。
     *
     * @param gateway 接收登录的进程
     * @param login 登录名
     * @param secret 口令
     * @return Cookie 值
     */
    private String login(Started gateway, String login, String secret) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base(gateway) + "/login"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("user=" + login + "&password=" + secret))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("登录没有被接受: " + response.statusCode());
        }
        assertFalse(response.body().contains(secret));
        for (String header : response.headers().allValues("set-cookie")) {
            if (header.startsWith("JASAGENT_SESSION=")) {
                String value = header.substring("JASAGENT_SESSION=".length());
                int end = value.indexOf(';');
                String token = end < 0 ? value : value.substring(0, end);
                if (token.isBlank()) {
                    throw new IllegalStateException("登录没有会话");
                }
                return token;
            }
        }
        throw new IllegalStateException("登录没有会话");
    }

    /**
     * 上传一份附件。客户端类型故意和文件头不一致。
     *
     * @param gateway 接收上传的进程
     * @param cookie 登录 Cookie
     * @param sessionId 会话编号
     * @param filename 客户端文件名
     * @param partType 客户端类型
     * @param file 文件字节
     * @return 附件编号
     */
    private String upload(Started gateway, String cookie, String sessionId, String filename, String partType, byte[] file)
            throws Exception {
        HttpResponse<byte[]> response = send(gateway, "POST", "/api/sessions/" + sessionId + "/attachments", cookie, form(filename, partType, file));
        if (response.statusCode() != 201) {
            throw new IllegalStateException("上传没有被接受: " + response.statusCode());
        }
        JsonNode node = JSON.readTree(response.body());
        assertFalse(new String(response.body(), StandardCharsets.UTF_8).contains("tenant/"));
        return node.get("attachmentId").asText();
    }

    /**
     * 从指定进程下载，并核对字节、类型和 nosniff。响应不改写登录 Cookie。
     *
     * @param gateway 提供下载的进程
     * @param cookie 登录 Cookie
     * @param sessionId 会话编号
     * @param attachmentId 附件编号
     * @param file 期望字节
     * @param mediaType 期望类型
     */
    private void assertBytes(Started gateway, String cookie, String sessionId, String attachmentId, byte[] file, String mediaType)
            throws Exception {
        HttpResponse<byte[]> response = send(gateway, "GET",
                "/api/sessions/" + sessionId + "/attachments/" + attachmentId, cookie, null);
        assertEquals(200, response.statusCode());
        assertArrayEquals(file, response.body());
        assertEquals(mediaType, response.headers().firstValue("content-type").orElse(""));
        assertEquals("nosniff", response.headers().firstValue("x-content-type-options").orElse(""));
        assertTrue(response.headers().allValues("set-cookie").stream().noneMatch(value -> value.contains("JASAGENT_SESSION")));
    }

    /**
     * 用另一条 JDBC 连接核对库名、正文和对象路径。
     *
     * @param tenantId 租户
     * @param sessionId 会话
     * @param userId 所有者
     * @param attachmentId 附件编号
     * @param file 期望字节
     * @param absent 不应出现在路径里的文件名片段
     */
    private static void assertStored(String tenantId, String sessionId, String userId, String attachmentId, byte[] file, String absent)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(MysqlAffinityStore.DEFAULT_URL, "root", UragPassword.read())) {
            try (Statement database = connection.createStatement();
                 ResultSet name = database.executeQuery("SELECT DATABASE()")) {
                assertTrue(name.next());
                assertEquals("jasagent", name.getString(1));
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT content, object_path FROM session_attachment
                    WHERE tenant_id = ? AND session_id = ? AND user_id = ? AND attachment_id = ?
                    """)) {
                statement.setString(1, tenantId);
                statement.setString(2, sessionId);
                statement.setString(3, userId);
                statement.setString(4, attachmentId);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertArrayEquals(file, rows.getBytes(1));
                    String path = rows.getString(2);
                    assertEquals("tenant/" + tenantId + "/session/" + sessionId + "/attachment/" + attachmentId, path);
                    assertFalse(path.contains(absent));
                    assertFalse(path.contains(".."));
                }
            }
        }
    }

    /**
     * 读取一条附件的状态码。
     *
     * @param gateway 进程
     * @param cookie 登录 Cookie
     * @param sessionId 会话编号
     * @param attachmentId 附件编号
     * @return HTTP 状态
     */
    private int status(Started gateway, String cookie, String sessionId, String attachmentId) throws Exception {
        return send(gateway, "GET", "/api/sessions/" + sessionId + "/attachments/" + attachmentId, cookie, null).statusCode();
    }

    /**
     * 删除一条附件的状态码。
     *
     * @param gateway 进程
     * @param cookie 登录 Cookie
     * @param sessionId 会话编号
     * @param attachmentId 附件编号
     * @return HTTP 状态
     */
    private int deleteStatus(Started gateway, String cookie, String sessionId, String attachmentId) throws Exception {
        return send(gateway, "DELETE", "/api/sessions/" + sessionId + "/attachments/" + attachmentId, cookie, null).statusCode();
    }

    /**
     * 发送一次请求。GET 和 DELETE 不带正文。
     *
     * @param gateway 进程
     * @param method 方法
     * @param path 路径
     * @param cookie 登录 Cookie，没有时为空
     * @param body 正文，没有时为空
     * @return 响应
     */
    private HttpResponse<byte[]> send(Started gateway, String method, String path, String cookie, byte[] body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base(gateway) + path)).timeout(Duration.ofSeconds(20));
        if (cookie != null) {
            builder.header("Cookie", "JASAGENT_SESSION=" + cookie);
        }
        if ("POST".equals(method)) {
            builder.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.GET();
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * 用原始请求行访问，避免客户端先把点段折掉。
     *
     * @param port 端口
     * @param path 原始路径
     * @param cookie 登录 Cookie
     * @return 状态码
     */
    private static int rawStatus(int port, String path, String cookie) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String request = "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n"
                    + "Cookie: JASAGENT_SESSION=" + cookie + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            String line = response.lines().findFirst().orElse("");
            String[] parts = line.split(" ");
            if (parts.length < 2) {
                throw new IllegalStateException("响应没有状态行");
            }
            return Integer.parseInt(parts[1]);
        }
    }

    /**
     * 拼一段文件。
     *
     * @param filename 文件名
     * @param type 客户端类型
     * @param file 文件字节
     * @return 请求体
     */
    private static byte[] form(String filename, String type, byte[] file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: "
                + type + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(file);
        out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    /**
     * 进程的根地址。
     *
     * @param gateway 进程
     * @return 不含路径的地址
     */
    private static String base(Started gateway) {
        return "http://127.0.0.1:" + gateway.port;
    }

    /**
     * 关掉进程。还没启动成功时什么都不做。
     *
     * @param gateway 进程，可以是 null
     */
    private static void close(Started gateway) {
        if (gateway != null) {
            gateway.context.close();
        }
    }

    /**
     * 删掉这次注册的账号和附件。说明里不放口令。
     *
     * @param logins 登录名
     */
    private static void cleanup(List<String> logins) {
        if (logins.isEmpty()) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(MysqlAffinityStore.DEFAULT_URL, "root", UragPassword.read())) {
            for (String login : logins) {
                delete(connection, "DELETE FROM session_attachment WHERE user_id = ?", login);
                delete(connection, "DELETE FROM agent_session WHERE user_id = ?", login);
                delete(connection, "DELETE FROM jasagent_login_session WHERE login = ?", login);
                delete(connection, "DELETE FROM jasagent_account WHERE login = ?", login);
                delete(connection, "DELETE FROM jasagent_gateway_affinity WHERE user_id = ?", login);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("测试账号无法清理");
        }
    }

    /**
     * 按登录名删除一行。表还不存在时忽略。
     *
     * @param connection 连接
     * @param sql 删除语句
     * @param login 登录名
     */
    private static void delete(Connection connection, String sql, String login) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, login);
            statement.executeUpdate();
        } catch (Exception ex) {
            String text = String.valueOf(ex.getMessage());
            if (text.contains("doesn't exist") || text.contains("does not exist")) {
                return;
            }
            throw new IllegalStateException("测试账号无法清理");
        }
    }

    /**
     * 生成口令。只用字母和数字。
     *
     * @return 口令
     */
    private static String secret() {
        return "pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 生成会话编号。
     *
     * @return 会话编号
     */
    private static String sessionId() {
        return "s" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 一个已经听上的网关进程。
     *
     * @param context Spring 上下文
     * @param port 监听端口
     */
    private record Started(ConfigurableApplicationContext context, int port) {
    }
}
