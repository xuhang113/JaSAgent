package dev.jasagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.jasagent.api.auth.AddUser;
import dev.jasagent.auth.AccountProfile;
import dev.jasagent.auth.AccountRoles;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.auth.MysqlAccountAuth;
import dev.jasagent.gateway.agent.AgentInstance;
import dev.jasagent.gateway.agent.AgentLiveness;
import dev.jasagent.gateway.media.AttachmentMedia;
import dev.jasagent.gateway.media.AttachmentSamples;
import dev.jasagent.gateway.store.MysqlAffinityStore;
import dev.jasagent.gateway.store.UragPassword;
import dev.jasagent.it.FixedTokenConfiguration;
import dev.jasagent.persist.SessionLedger;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件留在网关。未登录、非所有者和超限请求都不把正文交给桩 agent。
 */
@SpringBootTest(properties = "jasagent.gateway.media-test=true")
@AutoConfigureMockMvc
@Import({FixedTokenConfiguration.class, dev.jasagent.it.SessionMediaAgentsConfiguration.class})
public class SessionMediaGatewayTest {
    private static final String BOUNDARY = "jasagent026boundary";
    private static final String MARKER = "MARK026MEDIA";
    private static final List<String> HITS = new CopyOnWriteArrayList<>();
    private static final List<String> LOGINS = new CopyOnWriteArrayList<>();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Path home;
    private static HttpServer server;
    private static int port;

    /**
     * 桩端口。发现配置在扫描包外面读取它。
     *
     * @return 已经听上的端口
     */
    public static int agentPort() {
        return port;
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MysqlAccountAuth accounts;
    @Autowired
    private SessionLedger sessions;
    @Autowired
    private AgentLiveness liveness;

    /**
     * 用桩充当 agent。家目录是空的，避免把真实用户迁成 admin。
     *
     * @param registry 测试属性
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        if (server == null) {
            home = Files.createTempDirectory("gw026-home");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                HITS.add(exchange.getRequestURI().getPath());
                byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            port = server.getAddress().getPort();
        }
        registry.add("jasagent.home", () -> home.toString());
        registry.add("jasagent.gateway.links.enabled", () -> "false");
        registry.add("jasagent.gateway.web-origin", () -> "http://127.0.0.1:" + port);
    }

    /**
     * 把桩标成存活，并清掉上一次的访问。
     */
    @BeforeEach
    void agentUp() {
        HITS.clear();
        liveness.markUp(new AgentInstance("agent-" + port, "127.0.0.1", port));
    }

    /**
     * 停掉桩，并删掉这次注册的账号、会话和附件。说明里不放口令。
     */
    @AfterAll
    static void cleanup() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        String password = UragPassword.read();
        try (Connection connection = DriverManager.getConnection(MysqlAffinityStore.DEFAULT_URL, "root", password)) {
            for (String login : LOGINS) {
                removeRows(connection, "DELETE FROM session_attachment WHERE user_id = ?", login);
                removeRows(connection, "DELETE FROM agent_session WHERE user_id = ?", login);
                removeRows(connection, "DELETE FROM agent_workspace WHERE user_id = ?", login);
                removeRows(connection, "DELETE FROM jasagent_login_session WHERE login = ?", login);
                removeRows(connection, "DELETE FROM jasagent_account WHERE login = ?", login);
                removeRows(connection, "DELETE FROM jasagent_gateway_affinity WHERE user_id = ?", login);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("测试账号无法清理");
        }
    }

    /**
     * 未登录的上传和下载都是 401 AUTH，大小写不同也一样。
     */
    @Test
    void anonymousUploadAndDownloadAreAuth() throws Exception {
        String sessionId = sessionId();
        mvc.perform(post("/api/sessions/" + sessionId + "/attachments").content(AttachmentSamples.png()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH"));
        mvc.perform(get("/api/sessions/" + sessionId + "/attachments/missing"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH"));
        mvc.perform(post("/API/sessions/" + sessionId + "/attachments").content(AttachmentSamples.png()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH"));
        mvc.perform(get("/api/sessions/" + sessionId + "/attachments/missing?token=query-token-026"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH"));
        assertTrue(HITS.isEmpty());
    }

    /**
     * 图片、语音和视频按文件头收下，并能取回同一份字节。下载不改登录 Cookie。
     */
    @Test
    void allowedImageAudioAndVideoRoundTrip() throws Exception {
        String secret = secret();
        Registered owner = register(secret);
        String sessionId = ownedSession(owner);
        String cookie = login(owner.login, secret);
        byte[] image = marked(AttachmentSamples.png());
        byte[] audio = AttachmentSamples.wav();
        byte[] video = AttachmentSamples.videoMp4();
        String imageId = upload(cookie, sessionId, "../../holiday.png", "image/svg+xml", image, "image/png", "holiday.png");
        String audioId = upload(cookie, sessionId, "clip.wav", "text/plain", audio, "audio/wav", "clip.wav");
        String videoId = upload(cookie, sessionId, "clip.mp4", "video/ogg", video, "video/mp4", "clip.mp4");
        assertDownload(cookie, sessionId, imageId, image, "image/png");
        assertDownload(cookie, sessionId, audioId, audio, "audio/wav");
        assertDownload(cookie, sessionId, videoId, video, "video/mp4");
        MvcResult queried = mvc.perform(get("/api/sessions/" + sessionId + "/attachments/" + imageId + "?token=query-token-026")
                        .cookie(session(cookie)))
                .andExpect(status().isOk())
                .andReturn();
        assertArrayEquals(image, queried.getResponse().getContentAsByteArray());
        assertFalse(queried.getResponse().getContentAsString().contains("query-token-026"));
        assertEquals("nosniff", queried.getResponse().getHeader("X-Content-Type-Options"));
        assertNoSessionCookie(queried);
        MvcResult listed = mvc.perform(get("/api/sessions/" + sessionId + "/attachments").cookie(session(cookie)))
                .andExpect(status().isOk())
                .andReturn();
        String list = listed.getResponse().getContentAsString();
        assertTrue(list.contains(imageId));
        assertTrue(list.contains(audioId));
        assertTrue(list.contains(videoId));
        assertFalse(list.contains("tenant/"));
        assertFalse(list.contains(MARKER));
        mvc.perform(delete("/api/sessions/" + sessionId + "/attachments/" + audioId).cookie(session(cookie)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/sessions/" + sessionId + "/attachments/" + audioId).cookie(session(cookie)))
                .andExpect(status().isNotFound());
        assertEquals(2, count(sessionId));
        assertTrue(HITS.isEmpty());
    }

    /**
     * 不在名单里的类型、两段文件和超限图片都不落库，也不转发。
     */
    @Test
    void rejectedTypeAndSizeLeaveNoRow() throws Exception {
        String secret = secret();
        Registered owner = register(secret);
        String sessionId = ownedSession(owner);
        String cookie = login(owner.login, secret);
        assertEquals(0, count(sessionId));
        reject(cookie, sessionId, "page.svg", "image/svg+xml", AttachmentSamples.text("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"), 415);
        reject(cookie, sessionId, "page.html", "text/html", AttachmentSamples.text("<!DOCTYPE html><html></html>"), 415);
        reject(cookie, sessionId, "page.xhtml", "application/xhtml+xml",
                AttachmentSamples.text("<html xmlns=\"http://www.w3.org/1999/xhtml\"></html>"), 415);
        reject(cookie, sessionId, "page.xml", "application/xml", AttachmentSamples.text("<?xml version=\"1.0\"?><a/>"), 415);
        reject(cookie, sessionId, "page.xml", "text/xml", AttachmentSamples.text("<xml></xml>"), 415);
        reject(cookie, sessionId, "movie.mp4", "video/mp4", AttachmentSamples.text("<html>not-video</html>"), 415);
        reject(cookie, sessionId, "movie.ogg", "video/ogg", AttachmentSamples.ogg("theora"), 415);
        reject(cookie, sessionId, "movie.avi", "video/avi", AttachmentSamples.riff("AVI "), 415);
        reject(cookie, sessionId, "movie.3gp", "video/3gpp", AttachmentSamples.ftyp("3gp4", "isom"), 415);
        byte[] both = twoFiles(AttachmentSamples.png(), AttachmentSamples.gif());
        MvcResult bad = mvc.perform(post("/api/sessions/" + sessionId + "/attachments")
                        .contentType("multipart/form-data; boundary=" + BOUNDARY)
                        .content(both)
                        .cookie(session(cookie)))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertTrue(bad.getResponse().getContentAsString().contains("\"errorCode\":\"BAD_REQUEST\""));
        byte[] tooBig = AttachmentSamples.pad(AttachmentSamples.png(), AttachmentMedia.IMAGE_LIMIT + 1);
        MvcResult large = mvc.perform(post("/api/sessions/" + sessionId + "/attachments")
                        .contentType("image/png")
                        .content(tooBig)
                        .cookie(session(cookie)))
                .andExpect(status().is(413))
                .andReturn();
        assertTrue(large.getResponse().getContentAsString().contains("\"errorCode\":\"PAYLOAD_TOO_LARGE\""));
        mvc.perform(put("/api/sessions/" + sessionId + "/attachments").cookie(session(cookie)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD"));
        assertEquals(0, count(sessionId));
        assertTrue(HITS.isEmpty());
    }

    /**
     * 别的租户不能读、删或猜到附件。空会话和点段路径也不转发。
     */
    @Test
    void tenantsCannotReadEachOthersAttachments() throws Exception {
        String secret = secret();
        Registered owner = register(secret);
        Registered other = register(secret);
        String ownId = ownedSession(owner);
        String otherId = ownedSession(other);
        String cookie = login(owner.login, secret);
        String otherCookie = login(other.login, secret);
        byte[] image = marked(AttachmentSamples.png());
        String attachmentId = upload(cookie, ownId, "a.png", "image/png", image, "image/png", "a.png");
        HITS.clear();
        mvc.perform(get("/api/sessions/" + ownId).cookie(session(cookie))).andExpect(status().isOk());
        assertTrue(HITS.contains("/api/sessions/" + ownId));
        HITS.clear();
        MvcResult forbidden = mvc.perform(get("/api/sessions/" + ownId + "/attachments/" + attachmentId).cookie(session(otherCookie)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertFalse(forbidden.getResponse().getContentAsString().contains(MARKER));
        mvc.perform(delete("/api/sessions/" + ownId + "/attachments/" + attachmentId).cookie(session(otherCookie)))
                .andExpect(status().isForbidden());
        MvcResult guessed = mvc.perform(get("/api/sessions/" + otherId + "/attachments/" + attachmentId).cookie(session(otherCookie)))
                .andExpect(status().isNotFound())
                .andReturn();
        assertFalse(guessed.getResponse().getContentAsString().contains(MARKER));
        assertDownload(cookie, ownId, attachmentId, image, "image/png");
        String missing = sessionId();
        mvc.perform(get("/api/sessions/" + missing).cookie(session(cookie))).andExpect(status().isOk());
        assertTrue(HITS.contains("/api/sessions/" + missing));
        HITS.clear();
        mvc.perform(get("/api/sessions/" + missing + "/attachments").cookie(session(cookie)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/sessions/" + ownId + "/attachments/../messages").cookie(session(cookie)));
        assertTrue(HITS.isEmpty());
        mvc.perform(get("/api/sessions/" + ownId + "/%61ttachments").cookie(session(cookie)))
                .andExpect(status().isOk());
        mvc.perform(get("/API/sessions/" + ownId + "/ATTACHMENTS").cookie(session(cookie)))
                .andExpect(status().isOk());
        assertTrue(HITS.isEmpty());
    }

    /**
     * admin 可以看集群和 Jaeger 入口，但不能凭这个身份取走别人的附件。
     */
    @Test
    void adminPagesAreNotAnAttachmentBypass() throws Exception {
        String secret = secret();
        Registered owner = register(secret);
        String ownId = ownedSession(owner);
        String cookie = login(owner.login, secret);
        byte[] image = marked(AttachmentSamples.png());
        String attachmentId = upload(cookie, ownId, "a.png", "image/png", image, "image/png", "a.png");
        String operator = loginName();
        LOGINS.add(operator);
        Path operatorHome = Files.createTempDirectory("gw026-operator");
        new LocalAuthService(operatorHome).addUser(new AddUser(operator, secret.toCharArray(), null, false)).toCompletableFuture().join();
        accounts.importOperators(operatorHome, (login, from, to) -> sessions.moveUser(from, login, to));
        AccountProfile admin = accounts.profile(operator);
        assertTrue(admin.admin());
        String adminSession = owned(admin.tenantId(), operator);
        String adminCookie = login(operator, secret);
        String adminAttachment = upload(adminCookie, adminSession, "ops.png", "image/png", image, "image/png", "ops.png");
        MvcResult denied = mvc.perform(get("/api/sessions/" + ownId + "/attachments/" + attachmentId).cookie(session(adminCookie)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertFalse(denied.getResponse().getContentAsString().contains(MARKER));
        mvc.perform(get("/api/sessions/" + adminSession + "/attachments/" + adminAttachment).cookie(session(cookie)))
                .andExpect(status().isForbidden());
        MvcResult cluster = mvc.perform(get("/admin/cluster").cookie(session(adminCookie))).andExpect(status().isOk()).andReturn();
        assertFalse(cluster.getResponse().getContentAsString().contains(MARKER));
        MvcResult jaeger = mvc.perform(get("/admin/jaeger").cookie(session(adminCookie))).andExpect(status().isBadGateway()).andReturn();
        assertFalse(jaeger.getResponse().getContentAsString().contains(MARKER));
        assertTrue(jaeger.getResponse().getContentAsString().contains("UPSTREAM"));
        mvc.perform(get("/admin/jaeger").cookie(session(cookie))).andExpect(status().isForbidden());
        mvc.perform(get("/api/cluster").cookie(session(cookie))).andExpect(status().isForbidden());
        assertDownload(cookie, ownId, attachmentId, image, "image/png");
    }

    /**
     * 上传一份附件并核对类型、大小和展示名。
     *
     * @param cookie 登录 Cookie
     * @param sessionId 会话编号
     * @param filename 客户端文件名
     * @param partType 客户端申报的类型
     * @param file 文件字节
     * @param mediaType 期望的探测类型
     * @param name 期望的展示名
     * @return 附件编号
     */
    private String upload(String cookie, String sessionId, String filename, String partType, byte[] file, String mediaType, String name)
            throws Exception {
        int before = count(sessionId);
        MvcResult result = mvc.perform(post("/api/sessions/" + sessionId + "/attachments")
                        .contentType("multipart/form-data; boundary=" + BOUNDARY)
                        .content(form(filename, partType, file))
                        .cookie(session(cookie)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        JsonNode node = JSON.readTree(body);
        assertEquals(mediaType, node.get("mediaType").asText());
        assertEquals(file.length, node.get("byteSize").asInt());
        assertEquals(name, node.get("name").asText());
        assertEquals(sessionId, node.get("sessionId").asText());
        assertFalse(body.contains("tenant/"));
        assertFalse(body.contains("objectPath"));
        assertEquals(before + 1, count(sessionId));
        assertNoSessionCookie(result);
        return node.get("attachmentId").asText();
    }

    /**
     * 下载并核对字节、类型和 nosniff。响应不写登录 Cookie。
     *
     * @param cookie 登录 Cookie
     * @param sessionId 会话编号
     * @param attachmentId 附件编号
     * @param file 期望字节
     * @param mediaType 期望类型
     */
    private void assertDownload(String cookie, String sessionId, String attachmentId, byte[] file, String mediaType) throws Exception {
        MvcResult result = mvc.perform(get("/api/sessions/" + sessionId + "/attachments/" + attachmentId).cookie(session(cookie)))
                .andExpect(status().isOk())
                .andReturn();
        assertArrayEquals(file, result.getResponse().getContentAsByteArray());
        assertEquals(mediaType, result.getResponse().getHeader("Content-Type"));
        assertEquals("nosniff", result.getResponse().getHeader("X-Content-Type-Options"));
        assertEquals("private", result.getResponse().getHeader("Cache-Control"));
        assertNoSessionCookie(result);
    }

    /**
     * 拒绝一份上传，并确认没有新增行。
     *
     * @param cookie 登录 Cookie
     * @param sessionId 会话编号
     * @param filename 客户端文件名
     * @param partType 客户端申报的类型
     * @param file 文件字节
     * @param status 期望状态
     */
    private void reject(String cookie, String sessionId, String filename, String partType, byte[] file, int status) throws Exception {
        int before = count(sessionId);
        MvcResult result = mvc.perform(post("/api/sessions/" + sessionId + "/attachments")
                        .contentType("multipart/form-data; boundary=" + BOUNDARY)
                        .content(form(filename, partType, file))
                        .cookie(session(cookie)))
                .andExpect(status().is(status))
                .andReturn();
        assertTrue(result.getResponse().getContentAsString().contains("\"errorCode\":\"UNSUPPORTED_MEDIA\""));
        assertEquals(before, count(sessionId));
    }

    /**
     * 在账本里放一场属于这个用户的会话。
     *
     * @param owner 注册结果
     * @return 会话编号
     */
    private String ownedSession(Registered owner) {
        return owned(owner.tenantId, owner.login);
    }

    /**
     * 写入一场会话。编号只含字母和数字。
     *
     * @param tenantId 租户
     * @param login 登录名
     * @return 会话编号
     */
    private String owned(String tenantId, String login) {
        String sessionId = sessionId();
        String now = Instant.now().toString();
        sessions.save(tenantId, sessionId, login, "会话", "m", "p", false, now, now, "{}");
        return sessionId;
    }

    /**
     * 数这场会话的附件行。
     *
     * @param sessionId 会话编号
     * @return 行数
     */
    private static int count(String sessionId) throws Exception {
        try (Connection connection = DriverManager.getConnection(MysqlAffinityStore.DEFAULT_URL, "root", UragPassword.read());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM session_attachment WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    /**
     * 注册一个普通用户。
     *
     * @param secret 口令
     * @return 注册结果
     */
    private Registered register(String secret) throws Exception {
        String login = loginName();
        LOGINS.add(login);
        MvcResult result = mvc.perform(post("/register")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("user=" + login + "&password=" + secret))
                .andExpect(status().isCreated())
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(secret));
        AccountProfile profile = accounts.profile(login);
        assertEquals(AccountRoles.USER, profile.role());
        return new Registered(login, profile.tenantId());
    }

    /**
     * 登录并返回 Cookie 值。响应里不出现口令。
     *
     * @param login 登录名
     * @param secret 口令
     * @return Cookie 值
     */
    private String login(String login, String secret) throws Exception {
        MvcResult result = mvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("user=" + login + "&password=" + secret))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains(secret));
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(header.startsWith("JASAGENT_SESSION="));
        int end = header.indexOf(';');
        return header.substring("JASAGENT_SESSION=".length(), end < 0 ? header.length() : end);
    }

    /**
     * 响应不能改写登录 Cookie。
     *
     * @param result 本次响应
     */
    private static void assertNoSessionCookie(MvcResult result) {
        for (String header : result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
            assertFalse(header.contains("JASAGENT_SESSION"));
        }
    }

    /**
     * 拼一段只含一个文件的 multipart。
     *
     * @param filename 文件名
     * @param type 客户端类型
     * @param file 文件字节
     * @return 请求体
     */
    private static byte[] form(String filename, String type, byte[] file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: "
                + type + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(file);
        out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    /**
     * 同一分隔符里放两段文件。
     *
     * @param first 第一段
     * @param second 第二段
     * @return 请求体
     */
    private static byte[] twoFiles(byte[] first, byte[] second) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(first);
        out.write(("\r\n--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"b.png\"\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(second);
        out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    /**
     * 在图片后面加上只有测试认得的标记，方便确认旁路没有带回正文。
     *
     * @param file 原始图片
     * @return 带标记的字节
     */
    private static byte[] marked(byte[] file) {
        byte[] mark = MARKER.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[file.length + mark.length];
        System.arraycopy(file, 0, out, 0, file.length);
        System.arraycopy(mark, 0, out, file.length, mark.length);
        return out;
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
     * 生成登录名。
     *
     * @return 登录名
     */
    private static String loginName() {
        return "u" + UUID.randomUUID().toString().replace("-", "");
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
     * 包一枚会话 Cookie。
     *
     * @param value 令牌
     * @return Cookie
     */
    private static Cookie session(String value) {
        return new Cookie("JASAGENT_SESSION", value);
    }

    /**
     * 按登录名删除一行。表还不存在时忽略。
     *
     * @param connection 数据库连接
     * @param sql 删除语句
     * @param login 登录名
     */
    private static void removeRows(Connection connection, String sql, String login) {
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
     * 这次注册出来的身份。
     *
     * @param login 登录名
     * @param tenantId 租户
     */
    private record Registered(String login, String tenantId) {
    }
}
