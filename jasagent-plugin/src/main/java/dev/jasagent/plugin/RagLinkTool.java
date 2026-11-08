package dev.jasagent.plugin;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.plugin.PluginTool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolSpec;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletionStage;

public final class RagLinkTool implements PluginTool {
    private final LinkFetcher fetcher;

    public RagLinkTool() {
        this(new HttpLinkFetcher());
    }

    public RagLinkTool(LinkFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("rag.link", "save a link into rag docs", false);
    }

    @Override
    public CompletionStage<String> call(ToolCall call) {
        return VirtualIo.supply(() -> save(call));
    }

    private String save(ToolCall call) {
        String url = call.arguments().get("url");
        String scope = call.arguments().get("scope");
        if (url == null || url.isBlank()) {
            throw new AgentException(ErrorCode.TOOL, "url");
        }
        if (!"user".equals(scope) && !"workspace".equals(scope)) {
            throw new AgentException(ErrorCode.TOOL, "scope");
        }
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new AgentException(ErrorCode.TOOL, "link scheme");
        }
        requireSafe(uri);
        LinkResponse response = fetcher.fetch(url);
        String name = fileName(uri, response.contentType());
        Path root = "user".equals(scope) ? call.userDirectory() : call.workspaceDirectory();
        if (root == null) {
            throw new AgentException(ErrorCode.TOOL, "scope directory");
        }
        Path docs = root.resolve("rag").resolve("docs").normalize();
        Path target = docs.resolve(name).normalize();
        if (!target.startsWith(docs)) {
            throw new AgentException(ErrorCode.TOOL, "link path");
        }
        try {
            Files.createDirectories(docs);
            Files.writeString(target, response.body() == null ? "" : response.body(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AgentException(ErrorCode.TOOL, ex.getMessage(), null, ex);
        }
        return "uri: " + name + "\n" + "docId: " + name;
    }

    private static void requireSafe(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new AgentException(ErrorCode.TOOL, "link path");
            }
        }
    }

    private static String fileName(URI uri, String contentType) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        if (base.isBlank() || base.indexOf('\\') >= 0) {
            throw new AgentException(ErrorCode.TOOL, "link name");
        }
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            return base;
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("markdown")) {
            return base + ".md";
        }
        return base + ".txt";
    }
}
