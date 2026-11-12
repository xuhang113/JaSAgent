package dev.jasagent.sidecar;

import java.nio.charset.StandardCharsets;

/**
 * sidecar 交回给 agent 的结果。拒绝时正文是固定的 JSON，只含原因码；转发时正文是上游原文。
 * {@code forwarded} 为假表示上游没有被拨号，或拨号没有成功。
 *
 * @param status HTTP 状态码
 * @param contentType 响应的内容类型
 * @param body 响应正文，不会是空引用
 * @param forwarded 这次是否把上游的响应交了回去
 */
public record EgressOutcome(int status, String contentType, byte[] body, boolean forwarded) {
    /**
     * 收成始终可写出的正文和内容类型。空正文用空数组，避免控制器再判断空引用。
     *
     * @param status HTTP 状态码
     * @param contentType 内容类型，可以是空
     * @param body 正文，可以是空
     * @param forwarded 是否已经转到上游并拿到响应
     */
    public EgressOutcome {
        contentType = contentType == null ? "" : contentType;
        body = body == null ? new byte[0] : body;
    }

    /**
     * 构造拒绝结果。原因码只接受本进程自己的常量，并做 JSON 转义，避免把调用方输入拼进正文。
     *
     * @param reason {@link EgressReasons} 里的原因码
     * @return 未转发的 JSON 结果
     */
    public static EgressOutcome rejected(String reason) {
        String safe = reason == null ? EgressReasons.REJECTED : reason.replace("\\", "\\\\").replace("\"", "\\\"");
        byte[] json = ("{\"admitted\":false,\"reason\":\"" + safe + "\"}").getBytes(StandardCharsets.UTF_8);
        return new EgressOutcome(EgressReasons.status(reason), "application/json;charset=UTF-8", json, false);
    }

    /**
     * 构造已经从上游拿到的结果。没有内容类型时用二进制流，避免调用方误把正文当成本进程的 JSON。
     *
     * @param status 上游状态码
     * @param contentType 上游内容类型，可以是空
     * @param body 上游正文
     * @return 已转发的结果
     */
    public static EgressOutcome forwarded(int status, String contentType, byte[] body) {
        String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        return new EgressOutcome(status, type, body == null ? new byte[0] : body, true);
    }
}
