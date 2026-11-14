package dev.jasagent.gateway.proxy;

import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 注册 Spring Cloud Gateway MVC 的 agent 转发路由。
 * 登录、会话、集群留在网关进程。控制台由 {@link WebConsoleRoutes} 反代到 jasagent-web，不在这里转发。
 */
@Configuration
public class GatewayProxyRoutes {
    /**
     * 先匹配要改写 JSON 的接口，其余 /api 原样转发。
     * 两条路由都先经过粘性选择，再由网关的 HTTP 客户端发到选中的实例。
     *
     * @param proxy 粘性转发和响应改写
     * @return 合并后的路由
     */
    @Bean
    public RouterFunction<ServerResponse> agentRoutes(StickyProxy proxy) {
        return GatewayRouterFunctions.route("agent-decorated")
                .route(proxy::decorated, HandlerFunctions.http())
                .filter(proxy::forward)
                .after(proxy.rewriteAfter())
                .build()
                .and(GatewayRouterFunctions.route("agent-create")
                        .route(proxy::creates, HandlerFunctions.http())
                        .filter(proxy::forward)
                        .after(proxy.rememberAfter())
                        .build())
                .and(GatewayRouterFunctions.route("agent-proxy")
                        .route(proxy::plain, HandlerFunctions.http())
                        .filter(proxy::forward)
                        .build());
    }
}
