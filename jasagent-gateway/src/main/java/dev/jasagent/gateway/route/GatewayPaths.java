package dev.jasagent.gateway.route;

import org.springframework.http.HttpMethod;

import java.util.regex.Pattern;

/**
 * 划分哪些请求留在网关，哪些转发给 agent，以及哪些在没有会话时也能访问。
 * agent 不再提供登录页和控制台。控制台页面不转发给 agent，由网关反代到 jasagent-web。
 * 集群和当前用户由网关自己回答。其余 /api 才进入粘性转发。
 */
public final class GatewayPaths {
    /**
     * 登录页。未登录可以打开，网关再把它反代到控制台上游。
     */
    public static final String ADMIN_LOGIN = "/admin/login";
    /**
     * 登录页脚本和样式的前缀。未登录可以读取这一段，不能读取其它控制台页面。
     */
    public static final String ADMIN_ASSETS = "/admin/assets/";
    /**
     * 单次执行详情的路径。事件流不走这里。
     */
    private static final Pattern EXECUTION_DETAIL = Pattern.compile("^/api/executions/[^/]+$");
    /**
     * 中断或恢复一次执行的路径。
     */
    private static final Pattern EXECUTION_ACTION = Pattern.compile("^/api/executions/[^/]+/(interrupt|resume)$");
    /**
     * 对一次执行上的通行证做决定的路径。
     */
    private static final Pattern EXECUTION_PASS = Pattern.compile("^/api/executions/[^/]+/passes/[^/]+$");

    /**
     * 禁止实例化。路径规则只通过静态方法使用。
     */
    private GatewayPaths() {
    }

    /**
     * 未登录也能访问的路径。只有健康检查、首页和登录跳转、登录和退出接口、登录页，以及登录页的静态资源。
     * 控制台业务页、当前用户、集群和要转发给 agent 的 /api 都不在这里。
     *
     * @param method 请求方法
     * @param path 请求路径，不含查询串
     * @return 可以匿名访问时为真
     */
    public static boolean anonymous(HttpMethod method, String path) {
        if (method == null || path == null || path.isBlank()) {
            return false;
        }
        if (HttpMethod.GET.equals(method) && ("/health".equals(path) || "/".equals(path) || "/login".equals(path))) {
            return true;
        }
        if (HttpMethod.POST.equals(method) && ("/login".equals(path) || "/logout".equals(path))) {
            return true;
        }
        if (HttpMethod.GET.equals(method) && ADMIN_LOGIN.equals(path)) {
            return true;
        }
        return HttpMethod.GET.equals(method) && (ADMIN_ASSETS.equals(path) || path.startsWith(ADMIN_ASSETS));
    }

    /**
     * 这个请求是否由网关自己处理，不转发给 agent。
     * 包括健康检查、登录、退出、首页跳转、控制台、当前用户和集群。
     * 控制台虽然留在这条规则里，页面正文来自 jasagent-web 上游，不来自网关内嵌文件。
     *
     * @param method 请求方法
     * @param path 请求路径，不含查询串
     * @return 留在网关时为真
     */
    public static boolean local(HttpMethod method, String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        if ("/admin".equals(path) || path.startsWith("/admin/")) {
            return true;
        }
        if ("/health".equals(path) && HttpMethod.GET.equals(method)) {
            return true;
        }
        if ("/login".equals(path) && (HttpMethod.GET.equals(method) || HttpMethod.POST.equals(method))) {
            return true;
        }
        if ("/logout".equals(path) && HttpMethod.POST.equals(method)) {
            return true;
        }
        if ("/".equals(path) && HttpMethod.GET.equals(method)) {
            return true;
        }
        if ("/api/me".equals(path) && HttpMethod.GET.equals(method)) {
            return true;
        }
        return "/api/cluster".equals(path) && HttpMethod.GET.equals(method);
    }

    /**
     * 这个请求是否要按粘性转到 agent。只放行 /api 下、且不是本网关自己回答的路径。
     *
     * @param method 请求方法
     * @param path 请求路径
     * @return 需要转发时为真
     */
    public static boolean proxied(HttpMethod method, String path) {
        return path != null && path.startsWith("/api/") && !local(method, path);
    }

    /**
     * 模型目录是否要在转发后补上 DeepSeek 和 grok-4.7。
     *
     * @param method 请求方法
     * @param path 请求路径
     * @return 是 GET /api/models 时为真
     */
    public static boolean models(HttpMethod method, String path) {
        return HttpMethod.GET.equals(method) && "/api/models".equals(path);
    }

    /**
     * 执行详情、中断、恢复和通行证决定是否要写上落到的实例。
     * 事件流不在这里改写，避免把长连接响应整段读进内存。
     *
     * @param method 请求方法
     * @param path 请求路径
     * @return 响应需要带上 agentInstanceId 和 agentAddress 时为真
     */
    public static boolean execution(HttpMethod method, String path) {
        if (path == null) {
            return false;
        }
        if (HttpMethod.GET.equals(method) && EXECUTION_DETAIL.matcher(path).matches()) {
            return true;
        }
        if (HttpMethod.POST.equals(method) && EXECUTION_ACTION.matcher(path).matches()) {
            return true;
        }
        return HttpMethod.POST.equals(method) && EXECUTION_PASS.matcher(path).matches();
    }

    /**
     * 转发之后是否要改写 JSON。模型目录和执行详情走这条，其它接口原样返回。
     *
     * @param method 请求方法
     * @param path 请求路径
     * @return 需要改写响应时为真
     */
    public static boolean decorated(HttpMethod method, String path) {
        return models(method, path) || execution(method, path);
    }
}
