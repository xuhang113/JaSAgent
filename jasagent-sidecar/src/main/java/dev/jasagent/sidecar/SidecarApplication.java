package dev.jasagent.sidecar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Map;

/**
 * 出站 sidecar 的进程入口。agent 要离开本机去访问外部 HTTP 时，把请求交到这里转发。
 * 进程内时钟和只读工具不经过本进程：它们在 agent 自己的进程里完成，不会打到本端口。
 * 默认放行的大模型主机不需要通行证；其余外部地址必须先向已有的 agentpass 校验并核销。
 * 监听 0.0.0.0 是为了让旁边的 agent 容器和运行时探针能连进来。密钥、通行证和上游正文都不写日志。
 */
@SpringBootApplication(scanBasePackages = "dev.jasagent.sidecar")
@EnableConfigurationProperties(SidecarProperties.class)
public class SidecarApplication {
    /**
     * 容器和本进程约定的 TCP 端口。镜像 {@code jasagent-sidecar:0.1.0} 把这个端口暴露为 8080。
     */
    public static final int PORT = 8080;
    /**
     * 容器内的监听地址。只绑回环地址时，同一网络里的 agent 连不上 sidecar。
     */
    public static final String ADDRESS = "0.0.0.0";

    /**
     * 在 {@link #ADDRESS}:{@link #PORT} 启动出站 sidecar。
     * 命令行里的同名参数可以覆盖地址、端口、通行证服务和额外放行主机。
     * 副作用是启动内嵌 Web 服务器并停在 Spring 生命周期里，直到进程退出。本方法不拨号到 agentpass，也不打开上游连接。
     *
     * @param args 交给 Spring 的命令行参数，可以覆盖端口、通行证服务根地址和额外放行主机
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SidecarApplication.class);
        application.setDefaultProperties(Map.of(
                "server.address", ADDRESS,
                "server.port", Integer.toString(PORT)));
        application.run(args);
    }
}
