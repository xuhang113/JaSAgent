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

/**
 * 把 http 或 https 链接的正文存进用户或工作区的 rag/docs。
 * 路径里的 . 和 .. 会被拒绝，避免写到语料目录外面。
 */
public final class RagLinkTool implements PluginTool {
    private final LinkFetcher fetcher;

    /**
     * 使用会真正发 HTTP 请求的抓取器。
     */
    public RagLinkTool() {
        this(new HttpLinkFetcher());
    }

    /**
     * 使用调用方给的抓取器，测试可以因此不访问网络。
     *
     * @param fetcher 按链接取回正文和内容类型的抓取器
     */
    public RagLinkTool(LinkFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * 公布工具名 rag.link 和给模型看的说明，并标明它不是内部工具。
     *
     * @return 名称为 rag.link、说明为 save a link into rag docs 的规格
     */
    @Override
    public ToolSpec spec() {
        return new ToolSpec("rag.link", "save a link into rag docs", false);
    }

    /**
     * 在虚拟线程上抓取链接并写入对应范围的语料目录。
     *
     * @param call 含 url 和 scope 的工具调用
     * @return 完成后的两行文本，分别是写入文件的 uri 和 docId
     */
    @Override
    public CompletionStage<String> call(ToolCall call) {
        return VirtualIo.supply(() -> save(call));
    }

    /**
     * 校验链接和范围，抓取正文，再写到该范围 rag/docs 下的安全文件名。
     * 副作用：创建 docs 目录并覆盖同名文件。范围、协议或路径不合法时以工具错误失败，不写文件。
     *
     * @param call 含 url、scope 以及用户或工作区目录的调用
     * @return 两行文本，uri 和 docId 都是最终文件名
     */
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

    /**
     * 拒绝路径里单独出现的 . 或 ..，防止链接把文件写到语料目录之外。
     * 副作用：不修改地址；发现危险路径段时抛出工具错误。
     *
     * @param uri 已经确认是 http 或 https 的链接
     */
    private static void requireSafe(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new AgentException(ErrorCode.TOOL, "link path");
            }
        }
    }

    /**
     * 从链接路径取出文件名，并补上语料目录接受的后缀。
     * 已是 .md 或 .txt 的名字保持不变；内容类型含 markdown 时补 .md，否则补 .txt。空名或带反斜杠的名字被拒绝。
     *
     * @param uri 链接地址，只用它的路径最后一段
     * @param contentType 响应的内容类型，可以为空
     * @return 可以安全放进 docs 目录的文件名
     */
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
