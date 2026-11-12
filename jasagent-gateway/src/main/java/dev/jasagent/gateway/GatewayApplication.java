package dev.jasagent.gateway;

import dev.jasagent.gateway.admin.GatewayAdminPages;
import dev.jasagent.gateway.admin.GatewayAdminStaticMount;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

import java.util.Map;

/**
 * 统一网关入口。浏览器只访问本进程的 127.0.0.1:18721。
 * 登录、会话和 /admin 控制台由本进程提供；agent 不再提供登录页和控制台。
 * 导入 {@link GatewayAdminStaticMount} 后，classpath:/gateway-admin/ 挂到 /admin/**。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = "dev.jasagent.gateway",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {GatewayAdminStaticMount.class, GatewayAdminPages.class}))
@Import(GatewayAdminStaticMount.class)
public class GatewayApplication {
    /**
     * 绑定 127.0.0.1:18721 并启动网关。
     * 副作用：启动 Spring，不返回。
     *
     * @param args 交给 Spring 的参数
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(GatewayApplication.class);
        application.setDefaultProperties(Map.of("server.address", "127.0.0.1", "server.port", "18721"));
        application.run(args);
    }
}
