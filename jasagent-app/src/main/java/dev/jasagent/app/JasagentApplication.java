package dev.jasagent.app;

import dev.jasagent.agentpass.PassSettings;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.persist.UragMysql;
import dev.jasagent.llm.GrokEnvironment;
import dev.jasagent.llm.egress.SidecarAddress;
import dev.jasagent.cli.CliCommands;
import dev.jasagent.controller.StorageStatus;
import dev.jasagent.controller.WorkspaceList;
import dev.jasagent.core.ContextWindow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机 agent 入口。普通子命令交给命令行，其余情况在 127.0.0.1:18731 启动服务。
 * 不扫描 dev.jasagent.web，避免把控制台页面再提供出来。登录页和控制台只在 jasagent-web，由网关反代。
 */
@SpringBootApplication(scanBasePackages = {"dev.jasagent.app", "dev.jasagent.controller"})
@EnableConfigurationProperties(AppRuntimeProperties.class)
public class JasagentApplication {
    /**
     * 第一个参数是子命令时执行命令行并用退出码结束进程；否则绑定本机 18731 端口启动 agent。
     * 副作用：命令行路径调用 System.exit；服务路径启动 Spring，不返回。
     *
     * @param args 原始命令行。server 会被剥掉后再交给 Spring
     */
    public static void main(String[] args) {
        if (dispatchesToCli(args)) {
            int code = CliCommands.run(args, Path.of(System.getProperty("user.home")));
            System.exit(code);
        }
        GrokEnvironment.installDefault();
        SidecarAddress.configure(System.getenv(SidecarAddress.ENV));
        SpringApplication application = new SpringApplication(JasagentApplication.class);
        application.setDefaultProperties(Map.of("server.address", "127.0.0.1", "server.port", Integer.toString(AgentPorts.PRIMARY)));
        application.run(serverArgs(args));
    }

    /**
     * 没有参数、第一个词是 server，或第一个词以横线开头时留给网页服务，其余非空参数走命令行。
     *
     * @param args 原始命令行
     * @return 应交给命令行时为 true
     */
    private static boolean dispatchesToCli(String[] args) {
        return args.length > 0 && !"server".equals(args[0]) && !args[0].startsWith("-");
    }

    /**
     * 若第一个词是 server，则丢掉它，把后面的参数交给 Spring；否则原样返回。
     *
     * @param args 原始命令行
     * @return 网页服务实际使用的参数
     */
    private static String[] serverArgs(String[] args) {
        if (args.length > 0 && "server".equals(args[0])) {
            return Arrays.copyOfRange(args, 1, args.length);
        }
        return args;
    }

    /**
     * 按家目录创建本机认证服务，供登录、用户和工作区选择使用。
     *
     * @param home 数据根目录，缺省是运行用户的主目录
     * @return 绑定该目录的认证服务
     */
    @Bean
    LocalAuthService localAuthService(@Value("${jasagent.home:${user.home}}") String home) {
        return new LocalAuthService(Path.of(home));
    }

    /**
     * 用认证服务和三项配置组装本机运行时。构造过程会尝试打开执行日志和检查点。
     *
     * @param localAuthService 已绑定家目录的认证服务
     * @param agentpassEnabled 是否启用通行证，对应 jasagent.agentpass.enabled
     * @param compressAt 上下文开始压缩的占用比例，对应 jasagent.context.compress-at
     * @param deepseekThinking 是否打开 DeepSeek 思考，对应 jasagent.deepseek.thinking
     * @param sidecarUrl 回环上的 sidecar 根地址，离开集群的 HTTP 发到它的 /v1/forward
     * @return 可供执行和会话接口使用的运行时。进程已配置 020 的 JDBC 地址时，通行证改走同一套库
     */
    @Bean
    LocalRuntime localRuntime(LocalAuthService localAuthService,
                              @Value("${jasagent.agentpass.enabled:true}") String agentpassEnabled,
                              @Value("${jasagent.context.compress-at:0.85}") String compressAt,
                              @Value("${jasagent.deepseek.thinking:false}") String deepseekThinking,
                              @Value("${jasagent.egress.enabled:false}") String egressEnabled,
                              @Value("${jasagent.rag.base-url:http://127.0.0.1:18751}") String ragBaseUrl,
                              @Value("${jasagent.egress.ticket-url:http://127.0.0.1:18741/v1/tickets}") String ticketUrl,
                              @Value("${jasagent.sidecar.url:http://127.0.0.1:18761}") String sidecarUrl) {
        SidecarAddress.configure(sidecarUrl);
        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put(PassSettings.ENABLED, agentpassEnabled);
        configuration.put(ContextWindow.COMPRESS_AT, compressAt);
        configuration.put("jasagent.deepseek.thinking", deepseekThinking);
        configuration.put("jasagent.egress.enabled", egressEnabled);
        configuration.put("jasagent.rag.base-url", ragBaseUrl);
        configuration.put("jasagent.egress.ticket-url", ticketUrl);
        copyDurableLedger(configuration);
        return new LocalRuntime(localAuthService, configuration);
    }

    /**
     * 进程里已经给出 020 的 JDBC 地址时，通行证改走同一套库。地址里不能带口令。口令不写进日志。
     * 没有这个环境变量时保持原来的目录策略，避免没有库的测试被本机口令文件带偏。
     * 副作用：向配置写入账本地址、账号和口令。
     *
     * @param configuration 即将交给运行时的配置
     */
    private static void copyDurableLedger(Map<String, String> configuration) {
        String url = System.getenv("JASAGENT_JDBC_URL");
        if (url == null || url.isBlank()) {
            return;
        }
        if (url.toLowerCase().contains("password=")) {
            throw new IllegalStateException("JDBC 地址不能携带口令");
        }
        String user = System.getenv("JASAGENT_JDBC_USER");
        String password = System.getenv("JASAGENT_JDBC_PASSWORD");
        if (password == null || password.isBlank()) {
            password = UragMysql.readPassword();
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("没有找到本机 MySQL 口令");
        }
        configuration.put(PassSettings.DURABLE, "mysql");
        configuration.put(PassSettings.JDBC_URL, url);
        configuration.put(PassSettings.JDBC_USER, user == null || user.isBlank() ? "root" : user);
        configuration.put(PassSettings.JDBC_PASSWORD, password);
    }

    /**
     * 把健康检查要的存储状态指到运行时的可达性判断。
     *
     * @param localRuntime 持有执行日志和检查点的运行时
     * @return 存储状态探针
     */
    @Bean
    StorageStatus storageStatus(LocalRuntime localRuntime) {
        return localRuntime::storageReachable;
    }

    /**
     * 把逗号分隔的工作区路径收成允许选择的列表。空白配置表示不预置路径。
     *
     * @param raw 逗号分隔的路径，各项会去掉首尾空白
     * @return 允许进入的工作区
     */
    @Bean
    WorkspaceList workspaceList(@Value("${jasagent.workspaces:}") String raw) {
        if (raw == null || raw.isBlank()) {
            return new WorkspaceList(List.of());
        }
        List<Path> paths = Arrays.stream(raw.split(",")).map(String::trim).filter(value -> !value.isEmpty()).map(Path::of).toList();
        return new WorkspaceList(paths);
    }
}
