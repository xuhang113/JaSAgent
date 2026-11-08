package dev.jasagent.api.pass;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

public final class ArgumentHashes {
    private ArgumentHashes() {
    }

    public static String ofMap(Map<String, String> arguments) {
        String canonical = arguments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
        return sha256(canonical);
    }

    public static String ofPlugin(String phase, String arguments, String modelOutput) {
        return sha256(phase + "\n" + empty(arguments) + "\n" + empty(modelOutput));
    }

    public static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new AgentException(ErrorCode.INTERNAL, "SHA-256", null, ex);
        }
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
