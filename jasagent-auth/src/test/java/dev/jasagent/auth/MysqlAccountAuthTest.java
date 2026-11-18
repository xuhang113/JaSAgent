package dev.jasagent.auth;

import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.exception.AgentException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号和登录会话写在已有 MySQL。口令只存摘要，进程重新打开后仍能登录。
 * 文件目录里的运维账号迁入后是 admin；自助注册始终是普通用户。
 */
class MysqlAccountAuthTest {
    /**
     * 两次注册得到不同租户，角色都是普通用户。库里的口令不是原文。
     * 关掉后再打开同一套库，空的家目录下仍能登录。
     */
    @Test
    void registrationIsOrdinaryAndSurvivesReopen() throws Exception {
        String password = databasePassword();
        String url = MysqlAccountAuth.DEFAULT_URL;
        assertTrue(url.startsWith("jdbc:mysql:"));
        assertFalse(url.toLowerCase().contains("password="));
        String first = login();
        String second = login();
        String secret = "pw-" + UUID.randomUUID().toString().substring(0, 8);
        Path empty = Files.createTempDirectory("auth020-empty");
        MysqlAccountAuth auth = MysqlAccountAuth.open(url, "root", password);
        try {
            AccountProfile one = auth.register(first, secret.toCharArray()).toCompletableFuture().join();
            AccountProfile two = auth.register(second, secret.toCharArray()).toCompletableFuture().join();
            assertEquals(AccountRoles.USER, one.role());
            assertEquals(AccountRoles.USER, two.role());
            assertFalse(one.admin());
            assertTrue(one.tenantId().startsWith("t"));
            assertNotEquals(one.tenantId(), two.tenantId());
            assertNotEquals(AccountRoles.LEGACY_TENANT, one.tenantId());
            assertTrue(auth.passwordIsHashed(first, secret));
            assertFalse(Files.exists(empty.resolve(first).resolve("jasagent").resolve("auth").resolve("password.hash")));
            AgentException duplicate = failure(() -> auth.register(first, secret.toCharArray()).toCompletableFuture().join());
            assertEquals(MysqlAccountAuth.ALREADY_EXISTS, duplicate.getMessage());
            assertFalse(duplicate.getMessage().contains(secret));
            AgentException blank = failure(() -> auth.register(login(), new char[0]).toCompletableFuture().join());
            assertFalse(blank.getMessage().contains(secret));
        } finally {
            auth.deleteAccount(first);
            auth.deleteAccount(second);
        }
        MysqlAccountAuth reopened = MysqlAccountAuth.open(url, "root", password);
        AccountProfile again = null;
        String kept = login();
        try {
            again = reopened.register(kept, secret.toCharArray()).toCompletableFuture().join();
            reopened = MysqlAccountAuth.open(url, "root", password);
            Session session = reopened.login(kept, secret.toCharArray()).toCompletableFuture().join();
            assertEquals(kept, reopened.authenticate(session.token()).toCompletableFuture().join().login());
            assertEquals(AccountRoles.USER, reopened.profile(kept).role());
            assertEquals(again.tenantId(), reopened.profile(kept).tenantId());
            assertFalse(reopened.toString().contains(password));
            assertFalse(reopened.toString().contains(secret));
        } finally {
            reopened.deleteAccount(kept);
        }
    }

    /**
     * 文件目录里的运维账号迁入后仍是 admin。已经自助注册的同名用户不会被提权。
     */
    @Test
    void importedOperatorStaysAdminAndDoesNotPromoteARegisteredUser() throws Exception {
        String password = databasePassword();
        MysqlAccountAuth auth = MysqlAccountAuth.open(MysqlAccountAuth.DEFAULT_URL, "root", password);
        String operator = login();
        String ordinary = login();
        String secret = "pw-" + UUID.randomUUID().toString().substring(0, 8);
        Path home = Files.createTempDirectory("auth020-home");
        AtomicInteger moves = new AtomicInteger();
        try {
            new LocalAuthService(home).addUser(new AddUser(operator, secret.toCharArray(), null, false)).toCompletableFuture().join();
            auth.importOperators(home, (login, from, to) -> {
                assertEquals(operator, login);
                assertEquals(AccountRoles.LEGACY_TENANT, from);
                assertTrue(to.startsWith("t"));
                moves.incrementAndGet();
            });
            AccountProfile imported = auth.profile(operator);
            assertEquals(AccountRoles.ADMIN, imported.role());
            assertTrue(imported.admin());
            auth.importOperators(home, (login, from, to) -> moves.incrementAndGet());
            assertEquals(1, moves.get());
            assertEquals(imported.tenantId(), auth.profile(operator).tenantId());
            Session session = auth.login(operator, secret.toCharArray()).toCompletableFuture().join();
            assertEquals(operator, auth.authenticate(session.token()).toCompletableFuture().join().login());
            auth.register(ordinary, secret.toCharArray()).toCompletableFuture().join();
            Path later = Files.createTempDirectory("auth020-later");
            new LocalAuthService(later).addUser(new AddUser(ordinary, secret.toCharArray(), null, false)).toCompletableFuture().join();
            auth.importOperators(later, (login, from, to) -> moves.incrementAndGet());
            assertEquals(AccountRoles.USER, auth.profile(ordinary).role());
            assertEquals(1, moves.get());
        } finally {
            auth.deleteAccount(operator);
            auth.deleteAccount(ordinary);
        }
    }

    /**
     * 取出异步失败里的认证异常。说明里不应再包一层无关文本。
     *
     * @param call 会失败的调用
     * @return 认证异常
     */
    private static AgentException failure(org.junit.jupiter.api.function.Executable call) {
        CompletionException thrown = assertThrows(CompletionException.class, call);
        Throwable cause = thrown.getCause() == null ? thrown : thrown.getCause();
        assertTrue(cause instanceof AgentException);
        return (AgentException) cause;
    }

    /**
     * 读取仓库外口令。读不到时失败，说明里没有口令。
     *
     * @return 本机 MySQL 口令
     */
    private static String databasePassword() throws Exception {
        Path file = Path.of(System.getProperty("user.home"), ".config", "urag-mysql-root.password");
        String password = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (password.isBlank()) {
            throw new IllegalStateException("没有找到本机 MySQL 口令");
        }
        return password;
    }

    /**
     * 生成一个合法且不重复的登录名。
     *
     * @return 登录名
     */
    private static String login() {
        return "u" + UUID.randomUUID().toString().replace("-", "");
    }
}
