package dev.jasagent.sidecar;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出站转发的 HTTP 入口。agent 把要访问的绝对地址放在 {@code X-Jasagent-Url}，
 * 把一次性证放在 {@code X-Jasagent-Ticket}。本控制器不记录头和正文。
 * 时钟和只读工具不应调用这条路径。
 */
@RestController
public class EgressController {
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
     * 接收 {@code /v1/forward} 并按同一方法转发。拒绝时返回 JSON；转发成功时返回上游的状态和正文。
     * 副作用由 {@link EgressService#forward(ForwardRequest)} 产生：可能访问 agentpass 和上游。
     * 响应不缓存，避免中间层记住一次核销结果或模型正文。
     *
     * @param request 当前 HTTP 请求，含目的地址、证明文和正文
     * @return 给 agent 的响应
     * @throws IOException 读取调用方正文失败时抛出，不附带正文内容
     */
    @RequestMapping(path = EgressPaths.FORWARD, method = {
            RequestMethod.GET,
            RequestMethod.HEAD,
            RequestMethod.POST,
            RequestMethod.PUT,
            RequestMethod.PATCH,
            RequestMethod.DELETE
    })
    public ResponseEntity<byte[]> forward(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        ForwardRequest forward = new ForwardRequest(
                request.getMethod(),
                request.getHeader(EgressHeaders.URL),
                headers(request),
                body,
                ticket(request),
                request.getHeader(EgressHeaders.TENANT),
                request.getHeader(EgressHeaders.USER),
                request.getHeader(EgressHeaders.SESSION),
                request.getHeader(EgressHeaders.EXECUTION),
                request.getHeader(EgressHeaders.TOOL));
        EgressOutcome outcome = egress.forward(forward);
        return ResponseEntity.status(outcome.status())
                .contentType(media(outcome.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(outcome.body());
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
