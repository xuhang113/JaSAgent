package dev.jasagent.gateway.proxy;

import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 注册控制台的反向代理路由。页面来自可配置的 jasagent-web 上游，不从 classpath 读取。
 * 登录、会话和 /api 仍由网关自己的控制器和粘性路由处理。
 */
@Configuration
public class WebConsoleRoutes {
    /**
     * 把 /admin 交给控制台上游。会话过滤会先拦住必须登录的页面。
     *
     * @param proxy 上游地址和失败时的错误响应
     * @return 控制台反代路由
     */
    @Bean
    public RouterFunction<ServerResponse> webConsoleRoute(WebConsoleProxy proxy) {
        return GatewayRouterFunctions.route("web-console")
                .route(proxy::console, HandlerFunctions.http())
                .filter(proxy::forward)
                .build();
    }
}
