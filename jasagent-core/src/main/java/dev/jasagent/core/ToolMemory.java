package dev.jasagent.core;

import dev.jasagent.api.context.CorrectionNote;
import dev.jasagent.api.context.CorrectionSource;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ChatMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把工具结果接进对话，并在助手引用了工具和用户都没有的片段时记下以工具为准的订正。
 */
public final class ToolMemory {
    /**
     * 匹配助手回复里的直引号或中文引号片段，用来发现和工具结果对不上的说法。
     */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"|\u201c([^\u201d]+)\u201d");

    /**
     * 若助手引号中的片段既不在工具结果里也不在用户消息里，就追加一条以工具结果为准的订正，并把非空工具结果接到历史后面。
     *
     * @param history 已有对话，用来找最近一条助手回复
     * @param toolResult 这次工具返回的文本；空白时不追加工具消息
     * @param userMessage 当前用户消息，其中出现的引号片段不算冲突
     * @param notes 订正收集列表；发现冲突时会追加一条。不能为空
     * @param sessionId 写入订正的会话标识
     * @param executionId 写入订正的执行标识
     * @return 接上工具结果后的新消息列表，不修改传入的历史
     */
    public List<ChatMessage> reconcile(
            List<ChatMessage> history,
            String toolResult,
            String userMessage,
            List<CorrectionNote> notes,
            String sessionId,
            String executionId) {
        if (notes == null) {
            throw new AgentException(ErrorCode.INTERNAL, "notes");
        }
        String assistant = "";
        if (history != null) {
            for (int index = history.size() - 1; index >= 0; index--) {
                ChatMessage message = history.get(index);
                if (message != null && "assistant".equals(message.role())) {
                    assistant = message.content() == null ? "" : message.content();
                    break;
                }
            }
        }
        String tool = toolResult == null ? "" : toolResult;
        String user = userMessage == null ? "" : userMessage;
        if (!tool.isBlank() && conflicts(assistant, tool, user)) {
            notes.add(new CorrectionNote(
                    UUID.randomUUID().toString(),
                    sessionId,
                    executionId,
                    Instant.now(),
                    CorrectionSource.TOOL_CONTRADICTION,
                    "以工具结果为准：" + tool));
        }
        List<ChatMessage> next = new ArrayList<>();
        if (history != null) {
            next.addAll(history);
        }
        if (toolResult != null && !tool.isBlank()) {
            next.add(ChatMessage.text("tool", toolResult));
        }
        return List.copyOf(next);
    }

    /**
     * 查看助手引号片段是否有一段既不出现在工具结果里，也不出现在用户消息里。
     *
     * @param assistant 最近一条助手回复
     * @param tool 工具结果
     * @param user 用户消息
     * @return 存在对不上的引号片段时为真
     */
    private static boolean conflicts(String assistant, String tool, String user) {
        Matcher matcher = QUOTED.matcher(assistant);
        while (matcher.find()) {
            String fragment = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (fragment != null && !fragment.isBlank() && !tool.contains(fragment) && !user.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
