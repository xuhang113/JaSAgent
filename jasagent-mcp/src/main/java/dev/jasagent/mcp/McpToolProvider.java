package dev.jasagent.mcp;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.llm.ToolDefinition;
import dev.jasagent.api.pass.Pass;
import dev.jasagent.api.pass.PassDecision;
import dev.jasagent.api.pass.PassGate;
import dev.jasagent.api.pass.PassRequest;
import dev.jasagent.api.tool.Tool;
import dev.jasagent.api.tool.ToolCallContext;
import dev.jasagent.api.tool.ToolSpec;
import dev.jasagent.api.workflow.ToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * 连接一批 MCP 服务器，并把远程工具注册成本地可调用的工具。
 * 注册名是 mcp、服务器标识和远程工具名用下划线接起来。一台服务器连不上时只记下它，不挡住其它服务器；同名工具则整批打开失败。
 */
public final class McpToolProvider implements ToolProvider, AutoCloseable {
    private final List<Tool> tools;
    private final List<ToolDefinition> definitions;
    private final List<String> unreachableServerIds;
    private final List<AutoCloseable> sessions;

    /**
     * 记住已经注册的工具、给模型的定义、连不上的服务器和还开着的会话。
     *
     * @param tools 可以调用的本地工具
     * @param definitions 与工具顺序对应的名称、说明和参数模式
     * @param unreachableServerIds 这次打开时连不上的服务器标识
     * @param sessions 打开成功、关闭时需要释放的会话
     */
    private McpToolProvider(List<Tool> tools, List<ToolDefinition> definitions, List<String> unreachableServerIds, List<AutoCloseable> sessions) {
        this.tools = tools;
        this.definitions = definitions;
        this.unreachableServerIds = unreachableServerIds;
        this.sessions = sessions;
    }

    /**
     * 在虚拟线程上连接这些服务器并完成握手。
     *
     * @param servers 要连接的服务器，顺序就是尝试顺序
     * @param passGate 每个远程工具调用前申请通行证的门，不能为空
     * @return 连接结束后的工具提供者；通行证门为空时阶段失败
     */
    public static CompletionStage<McpToolProvider> open(List<McpServerConfig> servers, PassGate passGate) {
        return VirtualIo.supply(() -> connect(servers, passGate));
    }

    /**
     * 返回给模型看的工具定义。
     *
     * @return 名称、说明和参数模式，顺序与工具列表一致
     */
    public List<ToolDefinition> definitions() {
        return definitions;
    }

    /**
     * 返回这次打开时连不上的服务器标识。
     *
     * @return 服务器标识；全部连上时为空
     */
    public List<String> unreachableServerIds() {
        return unreachableServerIds;
    }

    /**
     * 返回已经注册、可以调用的工具。
     *
     * @return 本地工具列表
     */
    @Override
    public List<Tool> tools() {
        return tools;
    }

    /**
     * 关闭打开时留下的会话。
     * 副作用：逐个关闭会话；某个会话关闭失败时吞掉异常，不掩盖调用方已经拿到的结果。
     */
    @Override
    public void close() {
        for (AutoCloseable session : sessions) {
            try {
                session.close();
            } catch (Exception ignored) {
                // 关闭失败不掩盖调用方已经得到的结果
            }
        }
    }

    /**
     * 逐台连接服务器并收集工具。
     * 副作用：连接过程中打开会话；中途出现重复工具名或其它运行时异常时，关掉已经打开的会话再抛出。
     *
     * @param servers 要连接的服务器
     * @param passGate 工具调用前使用的通行证门，不能为空
     * @return 工具、定义、不可达服务器和会话都已收好的提供者
     */
    private static McpToolProvider connect(List<McpServerConfig> servers, PassGate passGate) {
        if (passGate == null) {
            throw new AgentException(ErrorCode.MCP, "通行证端口不能为空");
        }
        List<AutoCloseable> opened = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        Map<String, BoundTool> bindings = new LinkedHashMap<>();
        try {
            for (McpServerConfig server : servers) {
                attach(server, passGate, opened, unreachable, bindings);
            }
            List<Tool> tools = new ArrayList<>();
            List<ToolDefinition> definitions = new ArrayList<>();
            for (BoundTool binding : bindings.values()) {
                tools.add(binding.tool());
                definitions.add(new ToolDefinition(binding.tool().spec().name(), binding.tool().spec().description(), binding.parametersJson()));
            }
            return new McpToolProvider(List.copyOf(tools), List.copyOf(definitions), List.copyOf(unreachable), List.copyOf(opened));
        } catch (RuntimeException ex) {
            for (AutoCloseable session : opened) {
                try {
                    session.close();
                } catch (Exception ignored) {
                    // 启动失败时尽量关掉已经连上的服务器
                }
            }
            throw ex;
        }
    }

    /**
     * 连接一台服务器，把它的工具登记进绑定表。
     * 副作用：成功时把会话放进已打开列表；连不上时把服务器标识放进不可达列表并关掉这次会话。同名工具会关掉会话后抛出。
     *
     * @param server 这一台服务器的配置
     * @param passGate 登记工具时带上的通行证门
     * @param opened 已经打开、失败时也要关掉的会话
     * @param unreachable 收集连不上的服务器标识
     * @param bindings 注册名到本地工具和参数模式的映射
     */
    private static void attach(McpServerConfig server, PassGate passGate, List<AutoCloseable> opened, List<String> unreachable, Map<String, BoundTool> bindings) {
        AutoCloseable session = null;
        try {
            if (server.transport() instanceof McpTransport.Http http) {
                HttpMcpSession httpSession = HttpMcpSession.open(http, server.timeout());
                session = httpSession;
                register(server.serverId(), httpSession.tools(), (name, arguments, executionId) -> httpSession.callTool(name, arguments, executionId), passGate, bindings);
            } else if (server.transport() instanceof McpTransport.Stdio stdio) {
                StdioMcpSession stdioSession = StdioMcpSession.open(stdio, server.timeout());
                session = stdioSession;
                register(server.serverId(), stdioSession.tools(), (name, arguments, executionId) -> stdioSession.callTool(name, arguments, executionId), passGate, bindings);
            } else {
                throw new AgentException(ErrorCode.MCP, "不支持的传输");
            }
            opened.add(session);
        } catch (DuplicateTool ex) {
            closeQuietly(session);
            throw new AgentException(ErrorCode.MCP, "同名工具 " + ex.getMessage());
        } catch (Exception ex) {
            closeQuietly(session);
            unreachable.add(server.serverId());
        }
    }

    /**
     * 把一台服务器的远程工具注册成带前缀的本地工具。
     * 副作用：向绑定表放入新工具；注册名已经存在时抛出重复工具异常，不覆盖旧的。
     *
     * @param serverId 服务器标识，用作工具名前缀
     * @param tools 这台服务器公布的远程工具
     * @param remote 真正发 tools/call 的会话调用
     * @param passGate 本地工具调用前使用的通行证门
     * @param bindings 注册名到工具的映射
     */
    private static void register(String serverId, List<McpProtocol.RemoteTool> tools, RemoteCall remote, PassGate passGate, Map<String, BoundTool> bindings) {
        for (McpProtocol.RemoteTool tool : tools) {
            String registered = "mcp_" + serverId + "_" + tool.name();
            if (bindings.containsKey(registered)) {
                throw new DuplicateTool(registered);
            }
            McpTool mcpTool = new McpTool(registered, tool.description(), remote, tool.name(), passGate);
            bindings.put(registered, new BoundTool(mcpTool, tool.parametersJson()));
        }
    }

    /**
     * 尽量关闭一台服务器的会话。
     * 副作用：会话为空时什么都不做；关闭失败时吞掉异常，让其它服务器继续处理。
     *
     * @param session 要关闭的会话，可以为空
     */
    private static void closeQuietly(AutoCloseable session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception ignored) {
            // 单个服务器失败不影响其余服务器
        }
    }

    /**
     * 两台服务器或两次登记得到了同一个本地工具名。
     * 消息就是冲突的注册名，外层把它包成 MCP 异常。
     */
    private static final class DuplicateTool extends RuntimeException {
        /**
         * 把冲突的注册名当成异常消息。
         *
         * @param name 已经存在的本地工具名
         */
        private DuplicateTool(String name) {
            super(name);
        }
    }

    /**
     * 一个已注册工具和它要交给模型的参数模式。
     *
     * @param tool 可调用的本地工具
     * @param parametersJson 远程工具的输入参数模式
     */
    private record BoundTool(McpTool tool, String parametersJson) {
    }

    /**
     * 向已经连上的那台服务器调用一个远程工具。
     */
    @FunctionalInterface
    private interface RemoteCall {
        /**
         * 按远程原名发送 tools/call。
         *
         * @param name 服务器上的工具名
         * @param argumentsJson 工具参数的 JSON 文本
         * @param executionId 当前执行标识
         * @return 远程文本和错误标志
         */
        McpProtocol.RemoteResult call(String name, String argumentsJson, String executionId);
    }

    /**
     * 一个已经加上服务器前缀的远程工具。
     * 真正调用前先按参数哈希申请通行证，不允许或不跳过时不发送远程请求。
     */
    private static final class McpTool implements Tool {
        private final String registeredName;
        private final String description;
        private final RemoteCall remote;
        private final String remoteName;
        private final PassGate passGate;

        /**
         * 记住注册名、说明、远程调用、服务器上的原名和通行证门。
         *
         * @param registeredName 本地注册名，含服务器前缀
         * @param description 服务器给的说明
         * @param remote 发往这台服务器的调用
         * @param remoteName 服务器上的原名
         * @param passGate 调用前申请通行证的门
         */
        private McpTool(String registeredName, String description, RemoteCall remote, String remoteName, PassGate passGate) {
            this.registeredName = registeredName;
            this.description = description;
            this.remote = remote;
            this.remoteName = remoteName;
            this.passGate = passGate;
        }

        /**
         * 公布注册名和远程说明，并标明它不是内部工具。
         *
         * @return 给目录和模型看的工具规格
         */
        @Override
        public ToolSpec spec() {
            return new ToolSpec(registeredName, description, false);
        }

        /**
         * 在虚拟线程上申请通行证并调用远程工具。
         *
         * @param context 用户、执行和参数 JSON
         * @return 远程结果或未执行说明；通行证或远程调用失败时阶段失败
         */
        @Override
        public CompletionStage<ToolResult> call(ToolCallContext context) {
            return VirtualIo.supply(() -> invoke(context));
        }

        /**
         * 申请通行证，允许或跳过时才按原名调用远程工具。
         * 参数为空引用时按空串计算哈希并发送。远程标成错误时返回文本但成功标志为假。其它决定不发送请求。
         *
         * @param context 本次调用的用户、执行和参数
         * @return 未执行时是通行证原因或未执行；执行后是远程文本和是否成功
         */
        private ToolResult invoke(ToolCallContext context) {
            try {
                String arguments = context.argumentsJson() == null ? "" : context.argumentsJson();
                Pass pass = awaitPass(context, hash(arguments));
                if (pass.decision() != PassDecision.ALLOW && pass.decision() != PassDecision.SKIPPED) {
                    String text = pass.reason() == null || pass.reason().isBlank() ? "未执行" : pass.reason();
                    return new ToolResult(text, false);
                }
                McpProtocol.RemoteResult result = remote.call(remoteName, arguments, context.executionId());
                return new ToolResult(result.text(), !result.error());
            } catch (AgentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new AgentException(ErrorCode.MCP, ex.getMessage() == null ? "mcp 调用失败" : ex.getMessage(), context.executionId(), ex);
            }
        }

        /**
         * 按注册名和参数哈希申请通行证，并等到决定返回。
         * 通行证或决定为空时抛出 MCP 异常。下层已经是代理异常的，保持原异常。
         *
         * @param context 提供用户和执行标识
         * @param hash 参数 JSON 的 SHA-256 十六进制
         * @return 带决定的通行证
         */
        private Pass awaitPass(ToolCallContext context, String hash) {
            try {
                Pass pass = passGate.acquire(new PassRequest(context.userId(), context.executionId(), registeredName, null, hash))
                        .toCompletableFuture()
                        .join();
                if (pass == null || pass.decision() == null) {
                    throw new AgentException(ErrorCode.MCP, "通行证为空", context.executionId(), null);
                }
                return pass;
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                if (cause instanceof AgentException agent) {
                    throw agent;
                }
                throw new AgentException(ErrorCode.MCP, cause.getMessage() == null ? "通行证失败" : cause.getMessage(), context.executionId(), cause);
            }
        }

        /**
         * 对参数文本的 UTF-8 字节计算 SHA-256，并写成小写十六进制。
         *
         * @param value 要哈希的参数 JSON
         * @return 64 位十六进制摘要；算法不可用时抛出 MCP 异常
         */
        private static String hash(String value) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException ex) {
                throw new AgentException(ErrorCode.MCP, "无法计算参数哈希", null, ex);
            }
        }
    }
}
