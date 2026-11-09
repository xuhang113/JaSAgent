package dev.jasagent.auth;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 用 PBKDF2-HMAC-SHA256 把登录口令收成可落盘的摘要，并按摘要中的盐和迭代次数重算比对。
 */
final class PasswordHashes {
    /**
     * 新口令固定使用的 PBKDF2 迭代次数，会写进摘要串；校验已有摘要时改读其中保存的次数。
     */
    static final int ITERATIONS = 210_000;
    /**
     * 每次为新口令生成的随机盐长度，单位是字节。
     */
    private static final int SALT_BYTES = 16;
    /**
     * 派生密钥的位数，对应摘要里保存的 32 字节密钥材料。
     */
    private static final int KEY_BITS = 256;

    /**
     * 禁止把口令工具当成实例使用，避免调用方误持有无状态对象。
     */
    private PasswordHashes() {
    }

    /**
     * 为明文口令生成带随机盐的 PBKDF2 摘要串，供用户目录中的口令文件保存。
     *
     * @param password 待保存的口令字符；本方法不修改、也不清空调用方数组
     * @return {@code pbkdf2-sha256$迭代次数$Base64盐$Base64密钥} 形式的摘要
     */
    static String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        return "pbkdf2-sha256$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
    }

    /**
     * 按存储摘要里的算法名、迭代次数和盐重算口令，并用常量时间比较密钥是否一致。
     *
     * @param password 登录时提交的口令字符
     * @param stored 先前落盘的摘要串；分段数量或算法名不对、或迭代次数不是整数时视为不匹配
     * @return 重算密钥与摘要中的密钥一致时为真
     */
    static boolean verify(char[] password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) {
            return false;
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            return false;
        }
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        byte[] actual = derive(password, salt, iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 用给定盐和迭代次数从口令派生密钥，结束时清掉密钥规格中的口令副本。算法不可用时作为内部错误抛出。
     *
     * @param password 参与派生的口令字符
     * @param salt 与这份摘要一起保存的盐
     * @param iterations PBKDF2 迭代次数
     * @return 派生出的密钥字节
     */
    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.INTERNAL, "无法派生口令", null, ex);
        } finally {
            spec.clearPassword();
        }
    }
}
