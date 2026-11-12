package dev.jasagent.trace.lineage;

import java.util.List;

/**
 * 按 executionId 查回的溯源。
 * 有请求行时 {@link #request()} 带上租户、用户、会话、agent、模型调用和工具调用。
 * {@link #citations()} 是这次执行里回答引用过的检索片段，没有引用时是空列表。
 *
 * @param request 这次执行的用户请求；还没记过请求时为 null
 * @param citations 这次执行的引用，按写入时间和引用标识排序
 */
public record ExecutionLineage(RequestTrace request, List<CitationTrace> citations) {

    /**
     * 引用列表收成不可变副本。
     * 没有引用时变成空列表，不保留 null。
     */
    public ExecutionLineage {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /**
     * 这次执行有没有记下用户请求。
     * 只有引用、没有请求行时也是假。
     *
     * @return 请求行存在时为真
     */
    public boolean recorded() {
        return request != null;
    }
}
