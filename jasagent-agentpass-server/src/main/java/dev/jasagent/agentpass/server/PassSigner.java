package dev.jasagent.agentpass.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 把通行证声明签成证明文，并在校验时核对签名。证明文是 {@code v1.载荷.签名}，载荷里没有登录令牌。
 * 密钥只留在本对象里，任何日志和异常消息都不写出密钥或整段证明文。
 */
public final class PassSigner {
    /**
     * 证明文的版本段。校验时必须原样等于这个值，避免把别的格式当成通行证。
     */
    public static final String VERSION = "v1";

    /**
     * 签名算法名。每次签名都新建 {@link Mac}，因为这个对象不能并发使用。
     */
    private static final String HMAC = "HmacSHA256";

    /**
     * 证明文最长字符数。更长的输入直接视为无效，避免对超大字符串做签名比较。
     */
    public static final int MAX_DOCUMENT_CHARS = 8192;

    private final byte[] key;
    private final boolean ephemeral;
    private final ObjectMapper mapper;

    /**
     * 保存密钥和是否为进程内临时密钥。外部通过 {@link #create(String)} 构造。
     *
     * @param key HMAC 密钥字节，不能为空
     * @param ephemeral 为真表示密钥是本次启动生成的，没有来自配置
     * @param mapper 只用来读写证明文里的 JSON 载荷
     */
    private PassSigner(byte[] key, boolean ephemeral, ObjectMapper mapper) {
        this.key = key;
        this.ephemeral = ephemeral;
        this.mapper = mapper;
    }

    /**
     * 按配置创建签名器。密钥为空白时拒绝，不再在内存里另生成一把。异常消息只有配置名，没有密钥。
     *
     * @param configuredKey 配置或环境变量里的密钥原文，不能为空白
     * @return 可以签发和打开证明文的签名器
     */
    public static PassSigner create(String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalStateException("jasagent.agentpass.signing-key");
        }
        return new PassSigner(configuredKey.getBytes(StandardCharsets.UTF_8), false, new ObjectMapper());
    }

    /**
     * 说明这把密钥是不是本进程临时生成的。日志只应记录这个布尔值，不能记录密钥本身。
     *
     * @return 没有使用外部配置的密钥时为真
     */
    public boolean ephemeral() {
        return ephemeral;
    }

    /**
     * 把声明签成证明文。签名覆盖的是载荷的 base64 文本，校验时用同一段文本重算。
     *
     * @param claims 要绑进证明文的字段，过期时间用 ISO-8601 写入
     * @return {@code v1.载荷.签名} 形式的证明文
     */
    public String sign(Claims claims) {
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("passId", claims.passId());
            payload.put("tenant", claims.tenant());
            payload.put("user", claims.user());
            payload.put("sessionId", claims.sessionId());
            payload.put("executionId", claims.executionId());
            payload.put("targetService", claims.targetService());
            payload.put("toolName", claims.toolName());
            payload.put("expiresAt", claims.expiresAt().toString());
            String body = encode(mapper.writeValueAsBytes(payload));
            String signature = encode(hmac(body));
            return VERSION + "." + body + "." + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("agentpass document");
        }
    }

    /**
     * 核对证明文的版本、签名和载荷。签名不对、超长或字段缺失时返回空，不抛出带原文的异常。
     *
     * @param document 调用方持有的证明文
     * @return 验签通过后的声明；无法证明时为空
     */
    public Optional<Claims> open(String document) {
        if (document == null || document.length() > MAX_DOCUMENT_CHARS) {
            return Optional.empty();
        }
        String[] parts = document.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0]) || parts[1].isEmpty() || parts[2].isEmpty()) {
            return Optional.empty();
        }
        byte[] actual;
        try {
            actual = decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (!encode(actual).equals(parts[2])) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(hmac(parts[1]), actual)) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(decode(parts[1]));
            String passId = text(node, "passId");
            String tenant = text(node, "tenant");
            String user = text(node, "user");
            String sessionId = text(node, "sessionId");
            String executionId = text(node, "executionId");
            String targetService = text(node, "targetService");
            String toolName = text(node, "toolName");
            String expiresAt = text(node, "expiresAt");
            if (passId == null || tenant == null || user == null || sessionId == null
                    || executionId == null || targetService == null || toolName == null || expiresAt == null) {
                return Optional.empty();
            }
            return Optional.of(new Claims(passId, tenant, user, sessionId, executionId, targetService, toolName,
                    Instant.parse(expiresAt)));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /**
     * 从载荷节点读取一个非空文本字段。缺字段、不是文本或只有空白时视为没有。
     *
     * @param node 证明文载荷
     * @param field 字段名
     * @return 去掉空白后的文本；缺失时为空
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    /**
     * 用本对象的密钥对载荷文本做 HMAC-SHA256。每次调用都新建 Mac。
     *
     * @param body 证明文中间那段 base64 文本
     * @return 签名字节
     */
    private byte[] hmac(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("agentpass signature");
        }
    }

    /**
     * 把字节编成不带填充的 base64url，便于放进证明文的一段。
     *
     * @param value 待编码的字节
     * @return 不带 {@code =} 填充的 base64url
     */
    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /**
     * 解码不带填充的 base64url。长度不是 4 的倍数时先补上 {@code =}。
     *
     * @param value 证明文中的一段
     * @return 原始字节
     */
    private static byte[] decode(String value) {
        int pad = (4 - value.length() % 4) % 4;
        return Base64.getUrlDecoder().decode(value + "=".repeat(pad));
    }

    /**
     * 写进证明文的绑定字段。会话这里是会话编号，不是登录令牌。
     *
     * @param passId 通行证编号
     * @param tenant 租户
     * @param user 登录名
     * @param sessionId 已登录会话的编号
     * @param executionId 执行编号
     * @param targetService 目标服务
     * @param toolName 工具名
     * @param expiresAt 过期时刻，到达该时刻后不能核销
     */
    public record Claims(
            String passId,
            String tenant,
            String user,
            String sessionId,
            String executionId,
            String targetService,
            String toolName,
            Instant expiresAt) {
    }
}
