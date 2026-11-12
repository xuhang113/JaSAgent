package dev.jasagent.agentpass.rag;

import java.util.List;

/**
 * 示例检索的响应。只有过滤器放行后才会由控制器生成。
 *
 * @param hits 命中的资料，可能为空列表
 */
public record SearchResponse(List<Hit> hits) {
}
