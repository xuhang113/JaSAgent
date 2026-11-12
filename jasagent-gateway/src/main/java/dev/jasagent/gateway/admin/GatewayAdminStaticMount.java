package dev.jasagent.gateway.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 网关管理控制台的静态资源挂载。
 * 只把构建产物接到浏览器路径，不注册登录、会话、集群或其它接口。
 * agent 进程的启动类只扫描 dev.jasagent.app、dev.jasagent.controller、dev.jasagent.web，
 * 不会加载本配置，因此 agent 不会把这份页面提供出去。
 * 网关进程要提供页面时，导入本类即可；页面转发控制器会一起进来。
 * 构建产物在 jasagent-gateway/src/main/resources/gateway-admin，打包后位于 classpath:/gateway-admin/。
 */
@Configuration
@Import(GatewayAdminPages.class)
public class GatewayAdminStaticMount implements WebMvcConfigurer {
    /**
     * 构建产物在 classpath 上的位置。末尾的斜线表示按目录查找 index.html 和 assets。
     */
    public static final String CLASSPATH_LOCATION = "classpath:/gateway-admin/";

    /**
     * 浏览器访问控制台时使用的路径前缀，和前端 Vite 的 base 一致。
     */
    public static final String PAGE_PREFIX = "/admin";

    /**
     * 静态文件的匹配模式。/admin 下面的 index.html 和 assets 都从构建产物目录取。
     */
    public static final String RESOURCE_PATTERN = "/admin/**";

    /**
     * 把 /admin 下的文件指到构建产物目录。
     * 副作用：只登记资源位置，不增加接口。
     *
     * @param registry Spring 用来登记静态资源的注册表；本方法往里面加一条 /admin/** 规则
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(RESOURCE_PATTERN).addResourceLocations(CLASSPATH_LOCATION);
    }
}
