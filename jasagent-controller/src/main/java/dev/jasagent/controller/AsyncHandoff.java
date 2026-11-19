package dev.jasagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.context.request.async.AsyncWebRequest;
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 把一次会等待的执行接口从 Tomcat 平台线程挪到虚拟线程。
 * 容器线程只负责开启 Servlet 异步并返回；恢复或中断里的等待在 {@link #THREAD} 上完成。
 */
final class AsyncHandoff {
    /**
     * 执行异步线程的名字前缀。名字里不放令牌、用户或正文。
     */
    static final String THREAD = "jasagent-runtime-";
    /**
     * Servlet 异步超时。0 表示容器不掐断这次等待；模型自己的超时仍然有效。
     */
    private static final long NO_CONTAINER_TIMEOUT = 0L;
    /**
     * 写 JSON 用的映射器。不记录写出的正文。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 禁止实例化。移交通过 {@link #start} 完成。
     */
    private AsyncHandoff() {
    }

    /**
     * 在请求线程上抄下身份，开启异步，然后立刻把任务交给虚拟线程。
     * 调用方返回后，容器线程可以回到线程池。
     *
     * @param request 当前请求
     * @param response 写回调用方的响应
     * @param action 在虚拟线程上执行的任务，返回要写成 JSON 的正文
     */
    static void start(HttpServletRequest request, HttpServletResponse response, Supplier<Map<String, Object>> action) {
        CallerCarry.Snapshot caller = CallerCarry.capture();
        AsyncContext async = open(request, response);
        Thread.ofVirtual().name(THREAD, 0).start(() -> caller.run(() -> finish(async, response, action)));
    }

    /**
     * 在 Spring 已经挂上的异步请求上开始 Servlet 异步，让调度器在控制器返回后不要结束响应。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @return 虚拟线程结束时要完成的异步上下文
     */
    private static AsyncContext open(HttpServletRequest request, HttpServletResponse response) {
        WebAsyncManager manager = WebAsyncUtils.getAsyncManager(request);
        AsyncWebRequest asyncWebRequest = manager.getAsyncWebRequest();
        if (asyncWebRequest == null) {
            asyncWebRequest = new StandardServletAsyncWebRequest(request, response);
            manager.setAsyncWebRequest(asyncWebRequest);
        }
        asyncWebRequest.setTimeout(NO_CONTAINER_TIMEOUT);
        asyncWebRequest.startAsync();
        return request.getAsyncContext();
    }

    /**
     * 在虚拟线程上跑完任务并写出 JSON。业务异常按认证 401、其余 400 写回，不把异常链里的地址或令牌写进正文。
     * 无论成败都结束异步，避免请求一直占着连接。
     *
     * @param async 容器异步上下文
     * @param response 写回调用方的响应
     * @param action 要执行的任务
     */
    private static void finish(AsyncContext async, HttpServletResponse response, Supplier<Map<String, Object>> action) {
        try {
            write(response, 200, action.get());
        } catch (AgentException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("executionId", ex.executionId());
            body.put("errorCode", ex.code().name());
            if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
                body.put("message", ex.getMessage());
            }
            int status = ex.code() == ErrorCode.AUTH ? 401 : 400;
            write(response, status, body);
        } catch (RuntimeException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("executionId", null);
            body.put("errorCode", "INTERNAL");
            write(response, 500, body);
        } finally {
            async.complete();
        }
    }

    /**
     * 把映射写成 JSON。写失败时不再抛出，调用方仍会结束异步。
     *
     * @param response 当前响应
     * @param status HTTP 状态码
     * @param body 要写出的字段；不包含令牌
     */
    private static void write(HttpServletResponse response, int status, Map<String, Object> body) {
        try {
            response.setStatus(status);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            JSON.writeValue(response.getOutputStream(), body == null ? Map.of() : body);
        } catch (IOException ex) {
            // 调用方已经断开时不再补写。
        }
    }
}
