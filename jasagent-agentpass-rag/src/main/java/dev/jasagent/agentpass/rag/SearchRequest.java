package dev.jasagent.agentpass.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 示例检索的请求正文。执行和会话只在要记溯源时才带上，证不放在正文里。
 *
 * @param query 要查找的文本
 * @param executionId 这次执行；空白时不记引用
 * @param sessionId 会话编号
 * @param answerId 回答编号；空白时改用执行编号
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchRequest(
        @JsonProperty("query") String query,
        @JsonProperty("executionId") String executionId,
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("answerId") String answerId) {
}
