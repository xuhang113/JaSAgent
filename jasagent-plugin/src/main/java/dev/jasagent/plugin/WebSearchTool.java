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

public final class WebSearchTool implements PluginTool {
    private final WebSearchClient client;

    public WebSearchTool() {
        this(new LocalWebSearch());
    }

    public WebSearchTool(WebSearchClient client) {
        this.client = client;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("web.search", "search the web", false);
    }

    @Override
    public CompletionStage<String> call(ToolCall call) {
        return VirtualIo.supply(() -> render(call));
    }

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

    private static final class LocalWebSearch implements WebSearchClient {
        @Override
        public List<SearchHit> search(String query) {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            return List.of(new SearchHit(query, "https://example.invalid/search?q=" + encoded, "local:" + query));
        }
    }
}
