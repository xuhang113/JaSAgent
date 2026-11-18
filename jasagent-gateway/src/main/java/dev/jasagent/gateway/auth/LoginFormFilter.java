package dev.jasagent.gateway.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在网关的表单过滤器改写正文之前，把登录表单收成可重复读取的参数。
 * 网关自带的表单过滤器会先读参数再换一层请求。登录接口如果赶在正文被读掉之后才取参数，登录名和口令都会是空的。
 * 这个过滤器只处理 POST /login 和 POST /register，不记录口令。
 * 它晚于转发头过滤器，仍早于 Spring 自带的表单过滤器，避免正文被提前读掉。
 */
@Component
@Order(LoginFormFilter.ORDER)
public class LoginFormFilter extends OncePerRequestFilter implements Ordered {
    /**
     * 晚于转发头过滤器两个优先级，仍早于容器的表单过滤器。数值越小越早。
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;
    /**
     * 请求属性名。登录接口从这里读登录名，不再依赖被表单过滤器换掉的参数表。
     */
    static final String USER = "jasagent.login.user";
    /**
     * 请求属性名。值是口令字符数组，用完由登录接口清掉。
     */
    static final String PASSWORD = "jasagent.login.password";

    /**
     * 返回固定顺序。转发头先处理，这里再读登录表单。
     *
     * @return {@link #ORDER}
     */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 只缓存登录和注册表单。其它请求原样交给后面的过滤器。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param chain 后面的过滤器和登录接口
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!credentialForm(request)) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        Map<String, List<String>> fields = parse(body);
        request.setAttribute(USER, first(fields, "user"));
        request.setAttribute(PASSWORD, first(fields, "password").toCharArray());
        chain.doFilter(new LoginRequest(request, body, fields), response);
    }

    /**
     * 登录和注册都用同一份表单。其它路径不读正文，避免把口令留在别的接口上。
     *
     * @param request 当前请求
     * @return 是 POST /login 或 POST /register 时为真
     */
    private static boolean credentialForm(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return "/login".equals(path) || "/register".equals(path);
    }

    /**
     * 按 application/x-www-form-urlencoded 拆登录名和口令。解码失败的片段丢掉，不把原文写进异常。
     *
     * @param body 原始表单正文
     * @return 字段名到取值列表
     */
    private static Map<String, List<String>> parse(byte[] body) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.isEmpty()) {
            return fields;
        }
        for (String pair : text.split("&")) {
            int split = pair.indexOf('=');
            String rawName = split >= 0 ? pair.substring(0, split) : pair;
            String rawValue = split >= 0 ? pair.substring(split + 1) : "";
            try {
                String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
                String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
                fields.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            } catch (IllegalArgumentException ex) {
                // 坏的百分号编码不能当口令使用。
            }
        }
        return fields;
    }

    /**
     * 取字段的第一个值。没有时是空串。
     *
     * @param fields 已经拆开的表单
     * @param name 字段名
     * @return 第一个取值
     */
    private static String first(Map<String, List<String>> fields, String name) {
        List<String> values = fields.get(name);
        if (values == null || values.isEmpty() || values.get(0) == null) {
            return "";
        }
        return values.get(0);
    }

    /**
     * 让登录接口和后面的表单过滤器都能再读到同一份正文和参数。
     */
    private static final class LoginRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private final Map<String, String[]> parameters;

        /**
         * 记下原始正文和已经拆好的参数。
         *
         * @param request 原始请求
         * @param body 表单字节
         * @param fields 拆开的字段
         */
        private LoginRequest(HttpServletRequest request, byte[] body, Map<String, List<String>> fields) {
            super(request);
            this.body = body;
            Map<String, String[]> copied = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
                copied.put(entry.getKey(), entry.getValue().toArray(String[]::new));
            }
            this.parameters = Collections.unmodifiableMap(copied);
        }

        /**
         * 再提供一份正文，供表单过滤器转发给上游时使用。登录接口本身不转发。
         *
         * @return 可从头读的输入流
         */
        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                /**
                 * 缓冲区里没有剩余字节时，正文已经读完。
                 *
                 * @return 读完时为真
                 */
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                /**
                 * 正文还在内存里，可以立刻读。
                 *
                 * @return 总是真
                 */
                @Override
                public boolean isReady() {
                    return true;
                }

                /**
                 * 这个包装只在当前线程同步读取，不注册非阻塞监听。
                 *
                 * @param readListener 容器传入的监听；这里不使用
                 */
                @Override
                public void setReadListener(ReadListener readListener) {
                }

                /**
                 * 读下一个正文字节。
                 *
                 * @return 字节；读完时为 -1
                 */
                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        /**
         * 用 UTF-8 再读正文。
         *
         * @return 字符读取器
         */
        @Override
        public BufferedReader getReader() {
            Charset charset = StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }

        /**
         * 返回表单字段。没有这个字段时为空。
         *
         * @param name 字段名
         * @return 第一个取值
         */
        @Override
        public String getParameter(String name) {
            String[] values = parameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        /**
         * 返回全部表单字段。
         *
         * @return 字段映射
         */
        @Override
        public Map<String, String[]> getParameterMap() {
            return parameters;
        }

        /**
         * 返回字段名。
         *
         * @return 字段名枚举
         */
        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        /**
         * 返回一个字段的全部取值。
         *
         * @param name 字段名
         * @return 取值；没有时为空
         */
        @Override
        public String[] getParameterValues(String name) {
            return parameters.get(name);
        }

        /**
         * 告诉后面的过滤器正文还有这么长。
         *
         * @return 字节数
         */
        @Override
        public int getContentLength() {
            return body.length;
        }

        /**
         * 告诉后面的过滤器正文还有这么长。
         *
         * @return 字节数
         */
        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
