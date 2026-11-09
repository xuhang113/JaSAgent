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

/**
 * 用子进程的标准输入输出维持一条 MCP 会话。
 * 请求按行写给子进程，响应按行读回；标准错误被抽干，避免子进程因为写满而堵住。
 */
final class StdioMcpSession implements McpProtocol.Exchange, AutoCloseable {
    private final Process process;
    private final BufferedWriter writer;
    private final Duration timeout;
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final Object lock = new Object();
    private List<McpProtocol.RemoteTool> tools = List.of();
    private boolean closed;

    /**
     * 记住已经启动的子进程、写入端和单次等待时间。
     *
     * @param process 正在运行的 MCP 子进程
     * @param writer 写向子进程标准输入的缓冲写入器
     * @param timeout 等待一条响应的最长时间
     */
    private StdioMcpSession(Process process, BufferedWriter writer, Duration timeout) {
        this.process = process;
        this.writer = writer;
        this.timeout = timeout;
    }

    /**
     * 启动命令并完成握手。
     * 副作用：创建子进程；握手失败时关掉这次会话再把异常抛出。空命令或进程启动失败时抛出 MCP 异常。
     *
     * @param transport 命令和额外环境变量
     * @param timeout 每条请求的最长等待
     * @return 已经拉回工具列表的会话
     */
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

    /**
     * 返回握手时拉到的远程工具。
     *
     * @return 远程工具列表，顺序与服务器分页结果一致
     */
    List<McpProtocol.RemoteTool> tools() {
        return tools;
    }

    /**
     * 按服务器上的原名调用工具。
     * 参数必须是 JSON；不是 JSON 时抛出 MCP 异常，不发送 tools/call。
     *
     * @param name 服务器上的工具名
     * @param argumentsJson 工具参数的 JSON 文本
     * @param executionId 当前执行标识，失败时写进异常
     * @return 远程文本，以及服务器是否把它标成错误
     */
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

    /**
     * 写一条带编号的请求，并在超时内等同一编号的响应。
     * 副作用：占用会话写锁，把等待登记进表；结束时移除这次等待。超时、写失败或响应错误都变成 MCP 异常。
     *
     * @param method MCP 方法名
     * @param params 方法参数，可以为空
     * @param executionId 当前执行标识
     * @return 响应里的 result 节点
     */
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

    /**
     * 向子进程写一条不带编号的通知。
     * 副作用：占用写锁，把一行报文写入标准输入并刷新；写入失败时抛出 MCP 异常。
     *
     * @param method 通知的方法名
     */
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

    /**
     * 标准输入输出不使用协议版本请求头，所以这里不保存版本。
     * 副作用：不修改会话状态。
     *
     * @param protocolVersion 初始化响应里的协议版本，此处忽略
     */
    @Override
    public void rememberProtocol(String protocolVersion) {
    }

    /**
     * 关闭写入端并停掉子进程。
     * 副作用：已关闭过则直接返回；否则关掉写入、销毁进程，两秒内不退出就强行销毁，并让还在等的请求以会话已关闭失败。
     */
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

    /**
     * 持续读子进程的每一行，把带编号且含结果或错误的报文交给对应等待。
     * 副作用：完成匹配的等待；空行和解析不了的行被跳过。输出结束或读取失败时，让所有还在等的请求失败。
     *
     * @param reader 子进程标准输出
     */
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

    /**
     * 让所有还没收到响应的请求以同一个异常失败。
     * 副作用：完成这些等待的异常，并清空等待表。
     *
     * @param ex 要传给等待方的异常
     */
    private void failPending(Exception ex) {
        pending.forEach((id, future) -> future.completeExceptionally(ex));
        pending.clear();
    }

    /**
     * 把流读到结束并关闭，避免子进程的标准错误把缓冲区写满。
     * 副作用：读完该流；进程已经退出导致读取断开时，吞掉这个异常。
     *
     * @param stream 要抽干的输入流，通常是子进程标准错误
     */
    private static void drain(InputStream stream) {
        try (stream) {
            stream.readAllBytes();
        } catch (IOException ignored) {
            // 进程结束时读取会断开
        }
    }
}
