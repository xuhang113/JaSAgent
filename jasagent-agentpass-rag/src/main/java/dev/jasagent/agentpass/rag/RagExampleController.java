package dev.jasagent.agentpass.rag;

import dev.jasagent.trace.lineage.RagHitCitation;
import dev.jasagent.trace.lineage.RequestLineage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 示例检索的 HTTP 入口。运行时访问 {@code POST /search}，证放在 {@code X-Jasagent-Ticket}。
 * 这个路径要求一次性证；{@code /health} 在豁免名单里，不需要证。
 */
@RestController
public class RagExampleController {
    private final SearchLog searches;
    private final RequestLineage lineage = new RequestLineage();

    /**
     * 注入检索执行记录。
     *
     * @param searches 已经进入业务的查询
     */
    public RagExampleController(SearchLog searches) {
        this.searches = searches;
    }

    /**
     * 报告示例服务已经起来。这个路径不要求通行证。
     *
     * @return {@code status=ok} 且 {@code service=rag}
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "rag");
    }

    /**
     * 在固定资料里检索。调用前通行证过滤器已经完成校验和核销。
     * 副作用是把这次查询记入 {@link SearchLog}。
     *
     * @param request 查询正文，可以为空对象
     * @return 命中列表
     */
    @PostMapping("/search")
    public SearchResponse search(@RequestBody(required = false) SearchRequest request) {
        String query = request == null || request.query() == null ? "" : request.query();
        searches.record(query);
        List<Hit> hits = ExampleCorpus.search(query);
        remember(request, hits);
        return new SearchResponse(hits);
    }

    /**
     * 把命中记成这次回答的引用。没有执行或会话时不记。写库失败不影响已经检索到的结果，也不写证。
     *
     * @param request 检索正文
     * @param hits 已经命中的资料
     */
    private void remember(SearchRequest request, List<Hit> hits) {
        if (request == null || hits == null || hits.isEmpty()) {
            return;
        }
        if (blank(request.executionId()) || blank(request.sessionId())) {
            return;
        }
        String answerId = blank(request.answerId()) ? request.executionId().trim() : request.answerId().trim();
        List<RagHitCitation> citations = new ArrayList<>();
        int index = 0;
        for (Hit hit : hits) {
            index++;
            String document = hit.source() == null || hit.source().isBlank() ? "rag" : hit.source();
            citations.add(RagHitCitation.rag(document, "chunk-" + index));
        }
        try {
            lineage.recordRagHits(request.executionId().trim(), request.sessionId().trim(), answerId, citations)
                    .toCompletableFuture()
                    .join();
        } catch (RuntimeException ex) {
            // 引用表暂时写不进去时，检索响应仍然返回。异常文本可能含连接信息，这里不记录。
        }
    }

    /**
     * 判断字段是不是没有可用内容。
     *
     * @param value 正文里的字段
     * @return 空或只有空白时为真
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
