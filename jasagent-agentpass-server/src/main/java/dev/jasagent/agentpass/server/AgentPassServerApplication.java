package dev.jasagent.agentpass.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Map;

/**
 * 一次性通行证服务的进程入口。只扫描本包，在本机 18741 端口接受领证、校验和核销。
 * 不启动页面，也不接管进程内 {@code PassGate} 的审批挂起。
 */
@SpringBootApplication(scanBasePackages = "dev.jasagent.agentpass.server")
@EnableConfigurationProperties(AgentPassServerProperties.class)
public class AgentPassServerApplication {
    /**
     * 本服务默认监听的 TCP 端口。只绑在回环地址上，避免把领证接口暴露到其他网卡。
     */
    public static final int PORT = 18741;

    /**
     * 在 127.0.0.1:18741 启动通行证服务。命令行里的同名参数可以覆盖端口和家目录。
     * 副作用是启动内嵌 Web 服务器并阻塞在 Spring 生命周期里，直到进程退出。
     *
     * @param args 交给 Spring 的命令行参数，可以覆盖端口、家目录和有效期
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AgentPassServerApplication.class);
        application.setDefaultProperties(Map.of(
                "server.address", "127.0.0.1",
                "server.port", Integer.toString(PORT)));
        application.run(args);
    }
}
