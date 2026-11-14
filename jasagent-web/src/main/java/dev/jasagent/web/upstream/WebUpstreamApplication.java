package dev.jasagent.web.upstream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;

/**
 * 控制台上游入口。只提供登录页和控制台页面，供网关反向代理。
 * 不提供登录接口，也不监听网关的 18721。
 */
@SpringBootApplication(scanBasePackages = "dev.jasagent.web.upstream")
public class WebUpstreamApplication {
    /**
     * 绑定 127.0.0.1:18722 并启动页面进程。
     * 副作用：启动 Spring，不返回。
     *
     * @param args 交给 Spring 的参数
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(WebUpstreamApplication.class);
        application.setDefaultProperties(Map.of(
                "server.address", WebUpstream.DEFAULT_HOST,
                "server.port", Integer.toString(WebUpstream.DEFAULT_PORT)));
        application.run(args);
    }
}
