package dev.jasagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

final class StdioMcpSession implements McpProtocol.Exchange, AutoCloseable {
    private final Process process;
    private final BufferedWriter writer;
    private final Duration timeout;
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final Object lock = new Object();
    private List<McpProtocol.RemoteTool> tools = List.of();
    private boolean closed;

    private StdioMcpSession(Process process, BufferedWriter writer, Duration timeout) {
        this.process = process;
        this.writer = writer;
        this.timeout = timeout;
    }

    static StdioMcpSession open(McpTransport.Stdio transport, Duration timeout) {
        if (transport.command().isEmpty()) {
            throw new AgentException(ErrorCode.MCP, "stdio 命令为空");
        }
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(transport.command());
            if (!transport.environment().isEmpty()) {
                builder.environment().putAll(transport.environment());
            }
            process = builder.start();
        } catch (IOException ex) {
            throw new AgentException(ErrorCode.MCP, "无法启动 mcp 子进程", null, ex);
        }
        Thread.ofVirtual().start(() -> drain(process.getErrorStream()));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        StdioMcpSession session = new StdioMcpSession(process, writer, timeout);
        Thread.ofVirtual().start(() -> session.readLoop(reader));
        try {
            session.tools = McpProtocol.load(session);
            return session;
        } catch (RuntimeException ex) {
            session.close();
            throw ex;
        }
    }

    List<McpProtocol.RemoteTool> tools() {
        return tools;
    }

    McpProtocol.RemoteResult callTool(String name, String argumentsJson, String executionId) {
        ObjectNode params = McpProtocol.MAPPER.createObjectNode();
        params.put("name", name);
        try {
            params.set("arguments", McpProtocol.MAPPER.readTree(argumentsJson));
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.MCP, "工具参数不是 JSON", executionId, ex);
        }
        return McpProtocol.content(request("tools/call", params, executionId));
    }

    @Override
    public JsonNode request(String method, JsonNode params, String executionId) {
        synchronized (lock) {
            int id = ids.incrementAndGet();
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);
            try {
                writer.write(McpProtocol.MAPPER.writeValueAsString(McpProtocol.request(id, method, params)));
                writer.write('\n');
                writer.flush();
                JsonNode response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return McpProtocol.result(response, executionId);
            } catch (AgentException ex) {
                pending.remove(id);
                throw ex;
            } catch (TimeoutException ex) {
                pending.remove(id);
                throw new AgentException(ErrorCode.MCP, "mcp 响应超时", executionId, ex);
            } catch (Exception ex) {
                pending.remove(id);
                Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof AgentException agent) {
                    throw agent;
                }
                throw new AgentException(ErrorCode.MCP, cause.getMessage() == null ? "mcp 请求失败" : cause.getMessage(), executionId, cause);
            }
        }
    }

    @Override
    public void notify(String method) {
        synchronized (lock) {
            try {
                writer.write(McpProtocol.MAPPER.writeValueAsString(McpProtocol.notification(method)));
                writer.write('\n');
                writer.flush();
            } catch (IOException ex) {
                throw new AgentException(ErrorCode.MCP, "mcp 通知失败", null, ex);
            }
        }
    }

    @Override
    public void rememberProtocol(String protocolVersion) {
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            writer.close();
        } catch (IOException ignored) {
            // 子进程可能已经退出
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        pending.forEach((id, future) -> future.completeExceptionally(new IOException("mcp 会话已关闭")));
    }

    private void readLoop(BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = McpProtocol.MAPPER.readTree(trimmed);
                } catch (Exception ex) {
                    continue;
                }
                if (!node.hasNonNull("id") || (!node.has("result") && !node.has("error"))) {
                    continue;
                }
                CompletableFuture<JsonNode> future = pending.remove(node.get("id").asInt());
                if (future != null) {
                    future.complete(node);
                }
            }
        } catch (IOException ex) {
            failPending(ex);
            return;
        }
        failPending(new IOException("mcp 子进程输出已结束"));
    }

    private void failPending(Exception ex) {
        pending.forEach((id, future) -> future.completeExceptionally(ex));
        pending.clear();
    }

    private static void drain(InputStream stream) {
        try (stream) {
            stream.readAllBytes();
        } catch (IOException ignored) {
            // 进程结束时读取会断开
        }
    }
}
