package dev.jasagent.plugin;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.plugin.PluginTool;
import dev.jasagent.api.tool.ToolCall;
import dev.jasagent.api.tool.ToolSpec;

import java.net.URI;
import java.util.concurrent.CompletionStage;

/**
 * 把 GitHub 仓库解析成仓库名、默认分支、主页和分支树链接。
 * 默认客户端不访问网络，分支固定为 main；仓库地址只接受 github.com。
 */
public final class GithubLinkTool implements PluginTool {
    private final GithubClient client;

    /**
     * 使用固定返回 main 的客户端，不查询 GitHub。
     */
    public GithubLinkTool() {
        this((owner, name) -> "main");
    }

    /**
     * 使用调用方给的客户端查询默认分支。
     *
     * @param client 按所有者和仓库名返回默认分支的客户端
     */
    public GithubLinkTool(GithubClient client) {
        this.client = client;
    }

    /**
     * 公布工具名 github.link 和给模型看的说明，并标明它不是内部工具。
     *
     * @return 名称为 github.link、说明为 github repository links 的规格
     */
    @Override
    public ToolSpec spec() {
        return new ToolSpec("github.link", "github repository links", false);
    }

    /**
     * 在虚拟线程上解析仓库并渲染四行链接。
     *
     * @param call 含 url 或 repo 的工具调用
     * @return 完成后的仓库、默认分支、主页和 tree 链接
     */
    @Override
    public CompletionStage<String> call(ToolCall call) {
        return VirtualIo.supply(() -> render(call));
    }

    /**
     * 从 url 或 repo 解析所有者和仓库，再向客户端要默认分支。
     * 两个参数都空、仓库格式不对或分支为空时以工具错误失败。tree 路径里的分支不会拿来代替默认分支。
     *
     * @param call 含 url 或 repo 的调用，url 优先
     * @return 四行文本：repository、defaultBranch、url 和 tree
     */
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

    /**
     * 从 owner/repo 文本或 GitHub 链接里取出所有者和仓库名。
     * 链接的主机必须是 github.com 或 www.github.com，仓库名末尾的 .git 会被去掉。
     *
     * @param text 仓库简写或 http、https 链接
     * @return 长度为 2 的数组，下标 0 是所有者，下标 1 是仓库名
     */
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

    /**
     * 去掉仓库名末尾的 .git 后缀。
     *
     * @param repo 路径里的仓库名
     * @return 没有 .git 后缀的仓库名；原来就没有时原样返回
     */
    private static String stripGit(String repo) {
        return repo.endsWith(".git") ? repo.substring(0, repo.length() - 4) : repo;
    }
}
