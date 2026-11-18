package dev.jasagent.gateway.ingress;

import dev.jasagent.api.auth.AddUser;
import dev.jasagent.auth.LocalAuthService;
import dev.jasagent.gateway.store.MysqlAffinityStore;
import dev.jasagent.gateway.store.UragPassword;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 给网关测试准备临时用户目录和仓库外的数据库口令文件。口令只存在于这次测试进程里。
 */
final class IngressFixtures {
    /**
     * 测试用户登录名。
     */
    static final String LOGIN = "ingress-user";
    /**
     * 测试用户口令。不是部署口令。
     */
    static final String PASSWORD = "test-password-1";

    /**
     * 禁止实例化。准备动作只通过静态方法做。
     */
    private IngressFixtures() {
    }

    /**
     * 把家目录、关闭长连接和口令文件写进测试属性。路径由这次调用自己持有，不被后一次覆盖。
     *
     * @param registry 测试属性
     */
    static void register(DynamicPropertyRegistry registry) throws Exception {
        Path home = Files.createTempDirectory("gw019-home");
        if (UragPassword.read().isBlank()) {
            throw new IllegalStateException("没有找到本机 MySQL 口令");
        }
        new LocalAuthService(home).addUser(new AddUser(LOGIN, PASSWORD.toCharArray(), null, false)).toCompletableFuture().join();
        registry.add("jasagent.home", home::toString);
        registry.add("jasagent.gateway.links.enabled", () -> "false");
        registry.add(MysqlAffinityStore.PASSWORD_FILE_PROPERTY, () -> UragPassword.defaultFile().toString());
    }
}
