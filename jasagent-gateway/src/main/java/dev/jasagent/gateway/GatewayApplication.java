package dev.jasagent.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

import java.util.Map;

/**
 * 统一网关入口。浏览器只访问本进程的 127.0.0.1:18721。
 * 登录、会话、健康检查和粘性转发留在本进程。控制台页面反代到 jasagent-web，不再挂载 gateway-admin。
 * 登录会话和存活探测都在网关进程内，本轮网关按单副本验收。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "dev.jasagent.gateway")
public class GatewayApplication {
    /**
     * 绑定 127.0.0.1:18721 并启动网关。环境或配置可以改端口，缺省仍是这个地址。
     * 副作用：启动 Spring，不返回。
     *
     * @param args 交给 Spring 的参数
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(GatewayApplication.class);
        application.setDefaultProperties(Map.of(
                "server.address", GatewayRuntime.DEFAULT_HOST,
                "server.port", Integer.toString(GatewayRuntime.DEFAULT_PORT)));
        application.run(args);
    }
}
