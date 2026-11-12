package dev.jasagent.agentpass.server;

import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.auth.LocalAuthService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * 在临时家目录里建一个已登录用户。口令只在内存里生成，用完立即清掉，不写进夹具或日志。
 */
final class TestUsers {
    /**
     * 禁止实例化。建用户和写策略都通过静态方法完成。
     */
    private TestUsers() {
    }

    /**
     * 创建用户并登录。第一个用户的调用方令牌可以为空；之后必须带已登录令牌。
     * 副作用是写下口令摘要和会话文件。口令字符在返回前被清掉。
     *
     * @param home 临时家目录
     * @param login 登录名
     * @param callerToken 已有用户之后，创建人的登录令牌；第一个用户可以空
     * @return 新会话，其中的令牌供领证请求使用
     */
    static Session register(Path home, String login, String callerToken) {
        LocalAuthService auth = new LocalAuthService(home);
        char[] password = password();
        try {
            auth.addUser(new AddUser(login, password, callerToken, true)).toCompletableFuture().join();
            return auth.login(login, password).toCompletableFuture().join();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 把策略写到该用户自己的 {@code auth/policies.yaml}。调用前用户目录必须已经建好。
     *
     * @param home 临时家目录
     * @param login 登录名
     * @param yaml 策略正文
     */
    static void writePolicy(Path home, String login, String yaml) throws IOException {
        Path file = home.resolve(login).resolve("jasagent").resolve("auth").resolve("policies.yaml");
        Files.writeString(file, yaml);
    }

    /**
     * 生成只在这次调用里使用的口令字符。不把结果写到任何文件。
     *
     * @return 随机十六进制字符
     */
    private static char[] password() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes).toCharArray();
    }
}
