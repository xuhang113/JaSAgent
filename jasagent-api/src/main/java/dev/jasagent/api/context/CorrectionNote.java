package dev.jasagent.api.context;

import java.time.Instant;

/**
 * 挂在某个会话上的一条纠正。
 * 同一会话的纠正按写入顺序进入模型上下文，正文就是要模型遵守的那段话。
 *
 * @param noteId 这条纠正的标识
 * @param sessionId 它所属的会话
 * @param executionId 写下它的那次执行
 * @param createdAt 写下的时刻
 * @param source 来源
 * @param text 放进上下文的正文
 */
public record CorrectionNote(String noteId, String sessionId, String executionId, Instant createdAt, CorrectionSource source, String text) {
}
