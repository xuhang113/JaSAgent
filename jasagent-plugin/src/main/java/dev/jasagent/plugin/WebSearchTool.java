package dev.jasagent.plugin;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.plugin.PluginTool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolSpec;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 网页检索插件工具。
 * 默认客户端不访问网络，只按查询词拼出一条本地示例结果；真正的检索由注入的客户端完成。
 */
public final class WebSearchTool implements PluginTool {
    private final WebSearchClient client;

    /**
     * 使用不联网的本地客户端。
     *
     * 副作用：不发起检索，只把本地客户端留到调用时再用。
     */
    public WebSearchTool() {
        this(new LocalWebSearch());
    }

    /**
     * 使用调用方给的检索客户端。
     *
     * @param client 按查询词返回命中的客户端
     */
    public WebSearchTool(WebSearchClient client) {
        this.client = client;
    }

    /**
     * 公布工具名 web.search 和给模型看的说明，并标明它不是内部工具。
     *
     * @return 名称为 web.search、说明为 search the web 的规格
     */
    @Override
    public ToolSpec spec() {
        return new ToolSpec("web.search", "search the web", false);
    }

    /**
     * 在虚拟线程上执行检索，并把命中渲染成文本。
     *
     * @param call 本次工具调用，参数里要有 query
     * @return 完成后的检索文本；没有命中时文本为空串
     */
    @Override
    public CompletionStage<String> call(ToolCall call) {
        return VirtualIo.supply(() -> render(call));
    }

    /**
     * 读取 query，检索后把每条命中写成标题、链接和摘要三行。
     * 查询缺失时以工具错误失败；多条命中之间用空行隔开。
     *
     * @param call 含参数的工具调用
     * @return 拼好的文本；客户端没有返回命中时为空串
     */
    private String render(ToolCall call) {
        String query = call.arguments().get("query");
        if (query == null || query.isBlank()) {
            throw new AgentException(ErrorCode.TOOL, "query");
        }
        List<SearchHit> hits = client.search(query);
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        List<String> blocks = new ArrayList<>();
        for (SearchHit hit : hits) {
            blocks.add(String.join("\n",
                    "title: " + hit.title(),
                    "url: " + hit.url(),
                    "summary: " + hit.summary()));
        }
        return String.join("\n\n", blocks);
    }

    /**
     * 不联网的检索客户端。
     * 它只把查询词编进一个无效域名上的链接，供没有外网时仍能走完工具链路。
     */
    private static final class LocalWebSearch implements WebSearchClient {
        /**
         * 把查询词做百分号编码后放进示例链接，摘要标成 local 加原查询。
         *
         * @param query 原始查询词
         * @return 只含这一条示例命中的列表
         */
        @Override
        public List<SearchHit> search(String query) {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            return List.of(new SearchHit(query, "https://example.invalid/search?q=" + encoded, "local:" + query));
        }
    }
}
