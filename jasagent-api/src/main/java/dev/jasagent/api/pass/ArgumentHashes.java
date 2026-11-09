package dev.jasagent.api.pass;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 为工具参数和插件阶段计算稳定的 SHA-256 摘要。相同参数无论原先的键顺序如何，都得到同一个摘要。
 */
public final class ArgumentHashes {
    /**
     * 阻止实例化。摘要只通过静态方法计算。
     */
    private ArgumentHashes() {
    }

    /**
     * 把字符串参数表按键名排序后拼成规范文本，再计算摘要。空表摘要对应空字符串。
     *
     * @param arguments 工具参数，键和值都是字符串
     * @return 规范文本的小写十六进制 SHA-256
     */
    public static String ofMap(Map<String, String> arguments) {
        String canonical = arguments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
        return sha256(canonical);
    }

    /**
     * 把插件阶段、参数和模型输出拼成规范文本后计算摘要。参数或模型输出为 null 时按空串参与拼接。
     *
     * @param phase 插件阶段名
     * @param arguments 该阶段的参数文本
     * @param modelOutput 该阶段看到的模型输出
     * @return 三段文本的小写十六进制 SHA-256
     */
    public static String ofPlugin(String phase, String arguments, String modelOutput) {
        return sha256(phase + "\n" + empty(arguments) + "\n" + empty(modelOutput));
    }

    /**
     * 计算一段规范文本的 SHA-256，并以小写十六进制返回。
     *
     * @param canonical 已经排好序、拼好分隔符的原文，按 UTF-8 编码后摘要
     * @return 64 位小写十六进制摘要
     * @throws AgentException 运行环境没有 SHA-256 时抛出，错误码为 INTERNAL
     */
    public static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "SHA-256", null, ex);
        }
    }

    /**
     * 把可能缺失的文本收成可拼接的字符串。
     *
     * @param value 原始文本
     * @return value 本身；value 为 null 时返回空串
     */
    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
