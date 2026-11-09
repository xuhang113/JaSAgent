package dev.jasagent.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 计算段正文的 SHA-256，供索引和质量门核对内容没有被换掉。
 */
public final class Hashes {
    /**
     * 哈希工具不保存摘要器之外的状态，每次调用自己取算法。
     * 副作用：不写入字段。
     */
    private Hashes() {
    }

    /**
     * 对正文的 UTF-8 字节计算 SHA-256，并写成小写十六进制。
     * 运行环境缺少该算法时抛出非法状态异常。
     *
     * @param content 要摘要的正文
     * @return 64 位十六进制摘要
     */
    public static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
