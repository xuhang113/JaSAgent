package dev.jasagent.sidecar;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncWebRequest;
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出站转发的 HTTP 入口。agent 把要访问的绝对地址放在 {@code X-Jasagent-Url}，
 * 把一次性证放在 {@code X-Jasagent-Ticket}。本控制器不记录头和正文。
 * 容器线程只负责把请求交给虚拟线程就返回。等待 agentpass、等待上游和抄写响应都在虚拟线程上，
 * 不占用 Tomcat 平台工作线程。时钟和只读工具不应调用这条路径。
 */
@RestController
public class EgressController {
    private static final Logger log = LoggerFactory.getLogger(EgressController.class);
    /**
     * 建议 Tomcat 使用的响应缓冲。真正按块刷出由 {@link EgressResponsePump} 完成。
     */
    private static final int CHUNK = 8192;
    /**
     * 出站虚拟线程的名字前缀。名字里不放地址、票据或正文。
     */
    static final String EGRESS_THREAD = "jasagent-egress-";
    /**
     * Servlet 异步超时。0 表示容器不掐断长流式响应；上游响应头的超时仍由客户端自己控制。
     */
    private static final long NO_CONTAINER_TIMEOUT = 0L;

    private final EgressService egress;

    /**
     * 绑定出站服务。
     *
     * @param egress 放行、核销和转发
     */
    public EgressController(EgressService egress) {
        this.egress = egress;
    }

    /**
     * 接收 {@code /v1/forward} 并按同一方法转发。容器线程启动异步后立即返回，
     * 票据校验、上游等待和按块抄写都在 {@link #EGRESS_THREAD} 虚拟线程上完成。
     * 拒绝时返回短 JSON；转发时把上游状态和正文按块写回。响应不缓存。
     *
     * @param request 当前 HTTP 请求，含目的地址、证明文和正文流
     * @param response 写回给 agent 的响应，正文按块刷出
     */
    @RequestMapping(path = EgressPaths.FORWARD, method = {
            RequestMethod.GET,
            RequestMethod.HEAD,
            RequestMethod.POST,
            RequestMethod.PUT,
            RequestMethod.PATCH,
            RequestMethod.DELETE
    })
    public void forward(HttpServletRequest request, HttpServletResponse response) {
        AsyncContext async = start(request, response);
        Thread.ofVirtual().name(EGRESS_THREAD, 0).start(() -> handle(async, request, response));
    }

    /**
     * 在 Spring 已经挂上的异步请求上开始 Servlet 异步，让调度器在本方法返回后不要结束响应。
     * 超时设为不由容器掐断。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @return 虚拟线程结束时要完成的异步上下文
     */
    private static AsyncContext start(HttpServletRequest request, HttpServletResponse response) {
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
     * 在虚拟线程上完成一次转发。等待和抄写都不回到 Tomcat 平台工作线程。
     * 失败时只记录异常类型。无论成功与否都结束异步，避免请求一直占着连接。
     *
     * @param async 容器异步上下文
     * @param request 当前请求
     * @param response 写回调用方的响应
     */
    private void handle(AsyncContext async, HttpServletRequest request, HttpServletResponse response) {
        try {
            ForwardRequest forward = new ForwardRequest(
                    request.getMethod(),
                    request.getHeader(EgressHeaders.URL),
                    headers(request),
                    ticket(request),
                    request.getHeader(EgressHeaders.TENANT),
                    request.getHeader(EgressHeaders.USER),
                    request.getHeader(EgressHeaders.SESSION),
                    request.getHeader(EgressHeaders.EXECUTION),
                    request.getHeader(EgressHeaders.TOOL));
            response.setHeader("Cache-Control", "no-store");
            if (!response.isCommitted()) {
                response.setBufferSize(CHUNK);
            }
            try (EgressExchange outcome = egress.forward(forward, request.getInputStream(), request.getContentLengthLong())) {
                if (!response.isCommitted()) {
                    response.setStatus(outcome.status());
                    response.setContentType(media(outcome.contentType()).toString());
                }
                new EgressResponsePump(response, outcome.body()).copy();
            }
            if (!response.isCommitted()) {
                response.flushBuffer();
            }
        } catch (RuntimeException | IOException ex) {
            log.info("egress forward failed kind={}", ex.getClass().getSimpleName());
            if (!response.isCommitted()) {
                writeFailure(response);
            }
        } finally {
            complete(async);
        }
    }

    /**
     * 在响应还没提交时写一条固定的上游失败 JSON。正文不含异常消息。
     *
     * @param response 当前响应
     */
    private static void writeFailure(HttpServletResponse response) {
        EgressExchange rejected = EgressExchange.rejected(EgressReasons.UPSTREAM_FAILED);
        try {
            response.setStatus(rejected.status());
            response.setContentType(media(rejected.contentType()).toString());
            new EgressResponsePump(response, rejected.body()).copy();
        } catch (IOException | RuntimeException ex) {
            log.info("egress failure write failed kind={}", ex.getClass().getSimpleName());
        } finally {
            rejected.close();
        }
    }

    /**
     * 结束异步请求。调用方已经断开或异步已经结束时不再向外抛。
     *
     * @param async 当前异步上下文
     */
    private static void complete(AsyncContext async) {
        try {
            async.complete();
        } catch (IllegalStateException ex) {
            // 容器已经结束这次异步，或调用方已经断开。
        }
    }

    /**
     * 取出证明文。优先票据头；只有票据头不存在时才读另一个通行证头。
     * 头存在但值为空白时不再去读另一个头，按没带可用的证处理。
     *
     * @param request 当前请求
     * @return 证明文；两个头都没有时为空串
     */
    private static String ticket(HttpServletRequest request) {
        String ticket = request.getHeader(EgressHeaders.TICKET);
        if (ticket != null) {
            return ticket;
        }
        String pass = request.getHeader(EgressHeaders.PASS);
        return pass == null ? "" : pass;
    }

    /**
     * 复制当前请求的全部头。过滤发生在服务转发之前，这里保持原样，便于服务认出通行证头再丢掉。
     *
     * @param request 当前请求
     * @return 头的名字到全部取值的映射
     */
    private static Map<String, List<String>> headers(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return Map.of();
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            List<String> values = new ArrayList<>();
            Enumeration<String> each = request.getHeaders(name);
            while (each != null && each.hasMoreElements()) {
                values.add(each.nextElement());
            }
            if (!values.isEmpty()) {
                headers.put(name, List.copyOf(values));
            }
        }
        return headers;
    }

    /**
     * 解析内容类型。上游给了无法识别的类型时改用二进制流，不把解析失败的原文写进日志。
     *
     * @param value 内容类型原文
     * @return 可以写到响应上的类型
     */
    private static MediaType media(String value) {
        if (value == null || value.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(value);
        } catch (InvalidMediaTypeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
