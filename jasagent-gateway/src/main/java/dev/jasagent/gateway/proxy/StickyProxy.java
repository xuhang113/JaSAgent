package dev.jasagent.gateway.proxy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.auth.AccountProfile;
import dev.jasagent.gateway.agent.AgentInstance;
import dev.jasagent.gateway.auth.GatewaySessionFilter;
import dev.jasagent.persist.SessionLedger;
import dev.jasagent.gateway.route.AgentCallHeaders;
import dev.jasagent.gateway.route.GatewayPaths;
import dev.jasagent.gateway.route.StickyAgentRouter;
import dev.jasagent.gateway.route.UpstreamRewriter;
import dev.jasagent.gateway.token.InternalToken;
import dev.jasagent.trace.lineage.RequestLineage;
import dev.jasagent.trace.lineage.RequestTraceCommand;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.cloud.gateway.server.mvc.filter.BodyFilterFunctions;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把已登录请求粘性转发到 agent，并带上约定的四个内部头。
 * 登录 Cookie 不转发出去。agent 不再接受用户登录，只核对内部令牌。
 */
public final class StickyProxy {
    /**
     * 请求属性名。改写执行详情时从这里取出落到的实例。
     */
    public static final String ROUTED = "jasagent.routed-instance";
    /**
     * 请求属性名。记下这次登录名，响应回来后写溯源。
     */
    static final String LINEAGE_USER = "jasagent.lineage-user";
    /**
     * 请求属性名。记下这次会话编号，响应回来后写溯源。
     */
    static final String LINEAGE_SESSION = "jasagent.lineage-session";
    /**
     * 请求属性名。记下这次用户自己的租户，列表响应只保留这个租户下的会话。
     */
    static final String LINEAGE_TENANT = "jasagent.lineage-tenant";
    /**
     * 写入溯源的网关名。只是标识，不放令牌。
     */
    private static final String GATEWAY_NAME = "jasagent-gateway";
    /**
     * 会话路径。第二段是会话编号，转发头用它，不用登录会话文件名。
     */
    private static final Pattern SESSION_PATH = Pattern.compile("^/api/sessions/([^/]+)(?:/.*)?$");
    /**
     * 会新建执行的消息路径。请求进入时就记下溯源。
     */
    private static final Pattern MESSAGE_PATH = Pattern.compile("^/api/sessions/[^/]+/messages$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StickyAgentRouter router;
    private final InternalToken token;
    private final String tenantId;
    private final RequestLineage lineage;
    private final SessionLedger sessions;

    /**
     * 绑定路由器、内部令牌、缺省租户和请求溯源。没有会话账本时不做转发前的归属拒绝。
     *
     * @param router 按用户选择仍存活的 agent
     * @param token 环境变量里的内部令牌
     * @param tenantId 账号上没有租户时才使用的租户 id
     * @param lineage 请求进入时写入的溯源；为空时仍转发，但不记
     */
    public StickyProxy(StickyAgentRouter router, InternalToken token, String tenantId, RequestLineage lineage) {
        this(router, token, tenantId, lineage, null);
    }

    /**
     * 绑定路由器、内部令牌、缺省租户、请求溯源和会话账本。
     * 转发头里的租户优先用当前账号自己的租户。他人会话在调用 agent 之前拒绝。
     *
     * @param router 按用户选择仍存活的 agent
     * @param token 环境变量里的内部令牌
     * @param tenantId 账号上没有租户时才使用的租户 id
     * @param lineage 请求进入时写入的溯源；为空时仍转发，但不记
     * @param sessions 同一份 agent_session 账本；为空时跳过归属拒绝
     */
    public StickyProxy(StickyAgentRouter router, InternalToken token, String tenantId, RequestLineage lineage, SessionLedger sessions) {
        this.router = router;
        this.token = token;
        this.tenantId = tenantId == null || tenantId.isBlank() ? AgentCallHeaders.TENANT_LOCAL : tenantId;
        this.lineage = lineage;
        this.sessions = sessions;
    }

    /**
     * 是否属于要改写 JSON 的转发。模型和执行详情走这里。
     *
     * @param request 当前请求
     * @return 需要改写响应时为真
     */
    public boolean decorated(ServerRequest request) {
        return GatewayPaths.decorated(request.method(), request.uri().getPath());
    }

    /**
     * 是否属于原样转发。事件流和其它接口走这里，不把正文读进内存。
     *
     * @param request 当前请求
     * @return 需要原样转发时为真
     */
    public boolean plain(ServerRequest request) {
        String path = request.uri().getPath();
        return GatewayPaths.proxied(request.method(), path)
                && !GatewayPaths.decorated(request.method(), path)
                && !createsExecution(request.method(), path)
                && !sessionList(request);
    }

    /**
     * 是否是当前用户的会话列表。响应要按账本滤掉他人的会话编号。
     *
     * @param request 当前请求
     * @return 是 GET /api/sessions 时为真
     */
    public boolean sessionList(ServerRequest request) {
        return HttpMethod.GET.equals(request.method()) && "/api/sessions".equals(request.uri().getPath());
    }

    /**
     * 是否会新建执行。这些响应要读出 executionId 再记溯源，不能和事件流混在一起。
     *
     * @param request 当前请求
     * @return 是新建执行的 POST 时为真
     */
    public boolean creates(ServerRequest request) {
        return createsExecution(request.method(), request.uri().getPath());
    }

    /**
     * 选好实例、写上四个内部头，再交给网关的 HTTP 转发。
     * 未配置令牌、没有会话或没有存活 agent 时直接返回 JSON，不访问 agent。
     * 失败正文不包含令牌或口令。
     *
     * @param request 浏览器请求
     * @param next 真正把请求发到 agent 的处理函数
     * @return agent 的响应，或网关自己的错误响应
     * @throws Exception 转发函数抛出时继续抛出
     */
    public ServerResponse forward(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        if (!token.configured()) {
            return json(HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL", "未配置内部令牌");
        }
        Session session = (Session) request.servletRequest().getAttribute(GatewaySessionFilter.SESSION);
        if (session == null) {
            return json(HttpStatus.UNAUTHORIZED, "AUTH", null);
        }
        String tenant = tenantOf(request);
        String path = request.uri().getPath();
        if (sessions != null && ChatSessionGate.targetsSession(path)) {
            try {
                List<SessionLedger.Row> rows = sessions.findBySessionId(ChatSessionGate.sessionId(path));
                if (ChatSessionGate.foreign(rows, tenant, session.login())) {
                    return json(HttpStatus.FORBIDDEN, "FORBIDDEN", null);
                }
            } catch (RuntimeException ex) {
                return json(HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL", null);
            }
        }
        AgentInstance instance;
        try {
            instance = router.route(tenant, session.login()).instance();
        } catch (AgentException ex) {
            return json(HttpStatus.SERVICE_UNAVAILABLE, "INTERNAL", null);
        }
        request.servletRequest().setAttribute(ROUTED, instance);
        String sessionHeader = sessionOnPath(path, session.sessionId());
        request.servletRequest().setAttribute(LINEAGE_USER, session.login());
        request.servletRequest().setAttribute(LINEAGE_TENANT, tenant);
        request.servletRequest().setAttribute(LINEAGE_SESSION, sessionHeader);
        ServerRequest forwarded = ServerRequest.from(request)
                .headers(headers -> AgentCallHeaders.apply(headers, token, tenant, session.login(), sessionHeader))
                .attribute(ROUTED, instance)
                .build();
        MvcUtils.setRequestUrl(forwarded, URI.create("http://" + instance.host() + ":" + instance.port()));
        return next.handle(forwarded);
    }

    /**
     * 新建执行的响应回来后，用正文里的 executionId 记下租户、用户、会话和 agent。
     * 正文原样返回。记不进去时也不改状态码，并且不写令牌。
     *
     * @return 改写响应正文的函数；这里只旁路记溯源
     */
    public BiFunction<ServerRequest, ServerResponse, ServerResponse> rememberAfter() {
        return BodyFilterFunctions.modifyResponseBody(String.class, String.class, MediaType.APPLICATION_JSON_VALUE, this::remember);
    }

    /**
     * 供路由在响应返回后调用。只改模型和执行详情的 JSON。
     *
     * @return 改写响应正文的函数
     */
    public BiFunction<ServerRequest, ServerResponse, ServerResponse> rewriteAfter() {
        return BodyFilterFunctions.modifyResponseBody(String.class, String.class, MediaType.APPLICATION_JSON_VALUE, this::rewrite);
    }

    /**
     * 会话列表回来后只留下账本里属于当前用户的行。不是 JSON 数组的正文原样返回。
     *
     * @return 改写列表正文的函数
     */
    public BiFunction<ServerRequest, ServerResponse, ServerResponse> keepOwnedListAfter() {
        return BodyFilterFunctions.modifyResponseBody(String.class, String.class, MediaType.APPLICATION_JSON_VALUE, this::keepOwnedList);
    }

    /**
     * 当前请求该用哪个租户。账号上有租户时用它，一人一租户；没有账号属性时才用配置里的缺省租户。
     *
     * @param request 浏览器请求
     * @return 非空白的租户 id
     */
    private String tenantOf(ServerRequest request) {
        Object account = request.servletRequest().getAttribute(GatewaySessionFilter.ACCOUNT);
        if (account instanceof AccountProfile profile && profile.tenantId() != null && !profile.tenantId().isBlank()) {
            return profile.tenantId();
        }
        return tenantId;
    }

    /**
     * 从会话列表里去掉不属于当前租户和用户的项。正文不是数组时不改，避免把非 JSON 的测试桩正文弄丢。
     *
     * @param request 转发时的请求
     * @param response agent 的响应
     * @param body 响应正文
     * @return 只含当前用户会话的数组，或原来的正文
     */
    private String keepOwnedList(ServerRequest request, ServerResponse response, String body) {
        if (body == null || response == null || !response.statusCode().is2xxSuccessful() || sessions == null) {
            return body;
        }
        if (!body.stripLeading().startsWith("[")) {
            return body;
        }
        Object tenantValue = request.servletRequest().getAttribute(LINEAGE_TENANT);
        Object userValue = request.servletRequest().getAttribute(LINEAGE_USER);
        String tenant = tenantValue == null ? "" : String.valueOf(tenantValue);
        String user = userValue == null ? "" : String.valueOf(userValue);
        if (tenant.isBlank() || user.isBlank()) {
            return "[]";
        }
        Set<String> owned = new HashSet<>();
        try {
            for (SessionLedger.Row row : sessions.list(tenant, user)) {
                owned.add(row.sessionId());
            }
            JsonNode node = JSON.readTree(body);
            if (!node.isArray()) {
                return body;
            }
            ArrayNode kept = JSON.createArrayNode();
            for (JsonNode item : node) {
                if (item != null && item.isObject() && owned.contains(item.path("sessionId").asText(""))) {
                    kept.add(item);
                }
            }
            return JSON.writeValueAsString(kept);
        } catch (RuntimeException ex) {
            return "[]";
        } catch (Exception ex) {
            return "[]";
        }
    }

    /**
     * 按路径改写 agent 的正文。实例优先从 Servlet 属性取，这样外层过滤器换成新请求后仍能找到。
     *
     * @param request 转发时的请求
     * @param response agent 的响应，这里不修改状态码
     * @param body 已经读成字符串的正文
     * @return 改写后的正文
     */
    private String rewrite(ServerRequest request, ServerResponse response, String body) {
        return UpstreamRewriter.rewrite(request.method(), request.uri().getPath(), body, routedInstance(request));
    }

    /**
     * 取出这次转发选中的实例。先看 Servlet 属性，再看函数式请求上的属性。
     *
     * @param request 转发时的请求
     * @return 选中的 agent；还没路由时为空
     */
    private static AgentInstance routedInstance(ServerRequest request) {
        Object routed = request.servletRequest().getAttribute(ROUTED);
        if (routed instanceof AgentInstance instance) {
            return instance;
        }
        Object attribute = request.attribute(ROUTED).orElse(null);
        if (attribute instanceof AgentInstance instance) {
            return instance;
        }
        return null;
    }

    /**
     * 路径里有会话编号时用它做转发头。没有时才用登录会话文件名。
     * 这样发消息时，agent 看到的会话和 URL 里的会话是同一个。
     *
     * @param path 请求路径
     * @param loginSessionId 登录会话文件名，可以是空
     * @return 写进 X-Jasagent-Session 的值
     */
    private static String sessionOnPath(String path, String loginSessionId) {
        if (path != null) {
            Matcher matcher = SESSION_PATH.matcher(path);
            if (matcher.matches() && matcher.group(1) != null && !matcher.group(1).isBlank()) {
                return matcher.group(1);
            }
        }
        return loginSessionId == null ? "" : loginSessionId;
    }

    /**
     * 从新建执行的 JSON 里取出 executionId 并记溯源。正文不改。
     *
     * @param request 转发时的请求
     * @param response agent 的响应
     * @param body 响应正文
     * @return 原来的正文
     */
    private String remember(ServerRequest request, ServerResponse response, String body) {
        if (body == null || body.isBlank() || response == null || !response.statusCode().is2xxSuccessful()) {
            return body;
        }
        try {
            Map<String, Object> fields = JSON.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() {});
            Object value = fields.get("executionId");
            String executionId = value == null ? "" : String.valueOf(value).trim();
            if (!acceptable(executionId)) {
                return body;
            }
            Object user = request.servletRequest().getAttribute(LINEAGE_USER);
            Object sessionId = request.servletRequest().getAttribute(LINEAGE_SESSION);
            String session = sessionId == null ? "" : String.valueOf(sessionId);
            Object tenantValue = request.servletRequest().getAttribute(LINEAGE_TENANT);
            String tenant = tenantValue == null || String.valueOf(tenantValue).isBlank() ? tenantId : String.valueOf(tenantValue);
            recordEntry(executionId,
                    tenant,
                    user == null ? "" : String.valueOf(user),
                    lineageSession(request.uri().getPath(), session, executionId),
                    routedInstance(request));
        } catch (RuntimeException ex) {
            return body;
        } catch (Exception ex) {
            return body;
        }
        return body;
    }

    /**
     * 只有新建执行的 POST 才在进入时记溯源。查询和事件流不改写原来的执行身份。
     *
     * @param method 请求方法
     * @param path 请求路径
     * @return 需要分配执行编号时为真
     */
    private static boolean createsExecution(HttpMethod method, String path) {
        if (!HttpMethod.POST.equals(method) || path == null) {
            return false;
        }
        return "/api/executions".equals(path) || MESSAGE_PATH.matcher(path).matches();
    }

    /**
     * 消息用路径上的会话。直接提交执行时还没有会话，溯源里的会话就用这次执行编号。
     *
     * @param path 请求路径
     * @param sessionHeader 即将写给 agent 的会话头
     * @param executionId 这次执行
     * @return 写入溯源的会话
     */
    private static String lineageSession(String path, String sessionHeader, String executionId) {
        if ("/api/executions".equals(path)) {
            return executionId;
        }
        return sessionHeader == null || sessionHeader.isBlank() ? executionId : sessionHeader;
    }

    /**
     * 请求已经选定 agent 后记下租户、用户、会话和实例。失败不打断转发，也不写令牌。
     *
     * @param executionId 这次执行
     * @param tenant 当前用户自己的租户
     * @param userId 登录名
     * @param sessionId 会话编号
     * @param instance 选中的 agent
     */
    private void recordEntry(String executionId, String tenant, String userId, String sessionId, AgentInstance instance) {
        if (lineage == null || !acceptable(executionId) || instance == null) {
            return;
        }
        try {
            lineage.recordRequest(new RequestTraceCommand(
                    null,
                    executionId,
                    tenant,
                    userId,
                    sessionId,
                    instance.instanceId(),
                    GATEWAY_NAME,
                    List.of(),
                    List.of())).toCompletableFuture().join();
        } catch (RuntimeException ex) {
            // 溯源库暂时写不进去时，请求仍然转发。这里不读取异常文本，避免把口令带出来。
        }
    }

    /**
     * 执行编号只接受长度合适、不含空白的标识。网关生成的 UUID 可以通过。
     *
     * @param executionId 候选编号
     * @return 可以入库并交给 agent 时为真
     */
    private static boolean acceptable(String executionId) {
        return executionId != null && executionId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,63}");
    }

    /**
     * 写一个不含令牌的 JSON 错误。message 为空时不放这个字段。
     *
     * @param status HTTP 状态
     * @param errorCode 错误码
     * @param message 给页面的固定说明，不能包含令牌或口令
     * @return JSON 响应
     */
    private static ServerResponse json(HttpStatus status, String errorCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", null);
        body.put("errorCode", errorCode);
        if (message != null) {
            body.put("message", message);
        }
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
