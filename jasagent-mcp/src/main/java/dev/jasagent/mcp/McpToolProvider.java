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

public final class McpToolProvider implements ToolProvider, AutoCloseable {
    private final List<Tool> tools;
    private final List<ToolDefinition> definitions;
    private final List<String> unreachableServerIds;
    private final List<AutoCloseable> sessions;

    private McpToolProvider(List<Tool> tools, List<ToolDefinition> definitions, List<String> unreachableServerIds, List<AutoCloseable> sessions) {
        this.tools = tools;
        this.definitions = definitions;
        this.unreachableServerIds = unreachableServerIds;
        this.sessions = sessions;
    }

    public static CompletionStage<McpToolProvider> open(List<McpServerConfig> servers, PassGate passGate) {
        return VirtualIo.supply(() -> connect(servers, passGate));
    }

    public List<ToolDefinition> definitions() {
        return definitions;
    }

    public List<String> unreachableServerIds() {
        return unreachableServerIds;
    }

    @Override
    public List<Tool> tools() {
        return tools;
    }

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

    private static final class DuplicateTool extends RuntimeException {
        private DuplicateTool(String name) {
            super(name);
        }
    }

    private record BoundTool(McpTool tool, String parametersJson) {
    }

    @FunctionalInterface
    private interface RemoteCall {
        McpProtocol.RemoteResult call(String name, String argumentsJson, String executionId);
    }

    private static final class McpTool implements Tool {
        private final String registeredName;
        private final String description;
        private final RemoteCall remote;
        private final String remoteName;
        private final PassGate passGate;

        private McpTool(String registeredName, String description, RemoteCall remote, String remoteName, PassGate passGate) {
            this.registeredName = registeredName;
            this.description = description;
            this.remote = remote;
            this.remoteName = remoteName;
            this.passGate = passGate;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(registeredName, description, false);
        }

        @Override
        public CompletionStage<ToolResult> call(ToolCallContext context) {
            return VirtualIo.supply(() -> invoke(context));
        }

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
