package dev.jasagent.core;

import dev.jasagent.api.llm.ChatMessage;

import java.util.List;

/**
 * 一次上下文准备的结果：即将送出的消息，以及本轮留下的压缩摘要。
 *
 * @param messages 系统提示、摘要、保留对话、订正和当前用户排好的消息
 * @param summary 压缩得到的摘要；这次没有压缩时为空串
 */
public record ContextPrepared(List<ChatMessage> messages, String summary) {
    /**
     * 把空消息列表收成不可变空列表，并把空摘要收成空串。
     *
     * @param messages 准备好的消息；空则换成空列表
     * @param summary 压缩摘要；空则换成空串
     */
    public ContextPrepared {
        messages = messages == null ? List.of() : List.copyOf(messages);
        summary = summary == null ? "" : summary;
    }
}
