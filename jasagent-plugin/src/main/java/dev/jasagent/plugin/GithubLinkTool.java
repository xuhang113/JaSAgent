package dev.jasagent.plugin;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.plugin.PluginTool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolSpec;

import java.net.URI;
import java.util.concurrent.CompletionStage;

public final class GithubLinkTool implements PluginTool {
    private final GithubClient client;

    public GithubLinkTool() {
        this((owner, name) -> "main");
    }

    public GithubLinkTool(GithubClient client) {
        this.client = client;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("github.link", "github repository links", false);
    }

    @Override
    public CompletionStage<String> call(ToolCall call) {
        return VirtualIo.supply(() -> render(call));
    }

    private String render(ToolCall call) {
        String url = call.arguments().get("url");
        String repo = call.arguments().get("repo");
        String raw = url != null && !url.isBlank() ? url : repo;
        if (raw == null || raw.isBlank()) {
            throw new AgentException(ErrorCode.TOOL, "repo");
        }
        String[] parts = ownerAndRepo(raw.trim());
        String branch = client.defaultBranch(parts[0], parts[1]);
        if (branch == null || branch.isBlank()) {
            throw new AgentException(ErrorCode.TOOL, "branch");
        }
        String full = parts[0] + "/" + parts[1];
        String home = "https://github.com/" + full;
        return String.join("\n",
                "repository: " + full,
                "defaultBranch: " + branch,
                "url: " + home,
                "tree: " + home + "/tree/" + branch);
    }

    private static String[] ownerAndRepo(String text) {
        if (text.contains("://")) {
            URI uri = URI.create(text);
            String host = uri.getHost();
            if (host == null || !(host.equalsIgnoreCase("github.com") || host.equalsIgnoreCase("www.github.com"))) {
                throw new AgentException(ErrorCode.TOOL, "github host");
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String[] parts = path.split("/");
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new AgentException(ErrorCode.TOOL, "github repo");
            }
            return new String[]{parts[0], stripGit(parts[1])};
        }
        String[] parts = text.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new AgentException(ErrorCode.TOOL, "github repo");
        }
        return new String[]{parts[0], stripGit(parts[1])};
    }

    private static String stripGit(String repo) {
        return repo.endsWith(".git") ? repo.substring(0, repo.length() - 4) : repo;
    }
}
