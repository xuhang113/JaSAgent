package dev.jasagent.agentpass.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;

/**
 * 示例检索服务。引入通行证 starter 后，{@code /agent/} 下的检索必须先核销一次性证。
 * 只扫描本包，默认监听本机 18751。这不是主工程里的检索管道。
 */
@SpringBootApplication(scanBasePackages = "dev.jasagent.agentpass.rag")
public class RagExampleApplication {
    /**
     * 示例检索默认监听的 TCP 端口。
     */
    public static final int PORT = 18751;

    /**
     * 在 127.0.0.1:18751 启动示例检索。命令行可以覆盖端口和通行证服务地址。
     * 副作用是启动内嵌 Web 服务器，直到进程退出。
     *
     * @param args 交给 Spring 的命令行参数
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(RagExampleApplication.class);
        application.setDefaultProperties(Map.of(
                "server.address", "127.0.0.1",
                "server.port", Integer.toString(PORT)));
        application.run(args);
    }
}
