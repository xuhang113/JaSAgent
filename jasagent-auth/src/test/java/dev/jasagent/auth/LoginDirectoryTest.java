package dev.jasagent.auth;

import dev.jasagent.api.auth.AddUser;
import dev.jasagent.api.auth.Catalog;
import dev.jasagent.api.auth.DirectoryLayout;
import dev.jasagent.api.auth.Session;
import dev.jasagent.api.auth.UserAccount;
import dev.jasagent.api.auth.UserDirectoryGuard;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核对本地建账、口令摘要、目录边界、规则技能合并，以及命令行会话指针。
 */
class LoginDirectoryTest {
    /**
     * 各用例共用的测试口令，只写进临时家目录，不代表任何真实凭证。
     */
    private static final char[] PASSWORD = "test-password-1".toCharArray();
    /**
     * 期望的文件权限：只有属主可读可写，用来核对口令、会话和命令行指针。
     */
    private static final Set<PosixFilePermission> OWNER_RW = Set.of(OWNER_READ, OWNER_WRITE);

    @TempDir
    Path temp;

    /**
     * 空家目录里不带调用方会话也能建出第一个用户，并在其 auth 目录写下非空口令摘要。断言失败则本测试失败。
     */
    @Test
    void firstUserCanBeCreated() throws Exception {
        LocalAuthService auth = new LocalAuthService(temp.resolve("home"));
        String login = loginName("first");
        UserAccount account = join(auth.addUser(new AddUser(login, PASSWORD, null, false)));
        Path hash = account.directory().resolve("auth").resolve("password.hash");
        assertEquals(temp.resolve("home").resolve(login).resolve("jasagent"), account.directory());
        assertTrue(Files.isRegularFile(hash));
        assertFalse(Files.readString(hash).isBlank());
    }

    /**
     * 已经有用户之后，不带本机已登录会话再创建第二个用户会得到认证错误，且不会写下第二个口令文件。断言失败则本测试失败。
     */
    @Test
    void secondUserRejectedWithoutLocalSession() {
        LocalAuthService auth = new LocalAuthService(temp.resolve("home"));
        String first = loginName("first");
        join(auth.addUser(new AddUser(first, PASSWORD, null, false)));
        String second = loginName("second");
        AgentException error = assertThrows(AgentException.class,
                () -> join(auth.addUser(new AddUser(second, PASSWORD, null, false))));
        assertEquals(ErrorCode.AUTH, error.code());
        assertFalse(Files.exists(temp.resolve("home").resolve(second).resolve("jasagent").resolve("auth").resolve("password.hash")));
    }

    /**
     * 第一个用户登录后，携带其令牌并标明本机命令，可以创建第二个用户并落出口令摘要。断言失败则本测试失败。
     */
    @Test
    void secondUserAcceptedWithLocalSession() {
        LocalAuthService auth = new LocalAuthService(temp.resolve("home"));
        String first = loginName("first");
        join(auth.addUser(new AddUser(first, PASSWORD, null, false)));
        Session session = join(auth.login(first, PASSWORD));
        String second = loginName("second");
        UserAccount created = join(auth.addUser(new AddUser(second, PASSWORD, session.token(), true)));
        assertTrue(Files.isRegularFile(created.directory().resolve("auth").resolve("password.hash")));
    }

    /**
     * 同一口令两次建账必须得到不同摘要，格式为带随机盐的 PBKDF2，错误口令和明文本身都不能出现在摘要里。断言失败则本测试失败。
     */
    @Test
    void passwordUsesPbkdf2AndRandomSalt() throws Exception {
        LocalAuthService auth = new LocalAuthService(temp.resolve("home"));
        String one = loginName("one");
        String two = loginName("two");
        join(auth.addUser(new AddUser(one, PASSWORD, null, false)));
        Session session = join(auth.login(one, PASSWORD));
        join(auth.addUser(new AddUser(two, PASSWORD, session.token(), true)));
        String storedOne = Files.readString(hashFile(temp.resolve("home"), one));
        String storedTwo = Files.readString(hashFile(temp.resolve("home"), two));
        assertNotEquals(storedOne, storedTwo);
        assertPbkdf2(storedOne, PASSWORD);
        assertPbkdf2(storedTwo, PASSWORD);
        assertFalse(matchesIndependent(storedOne, "wrong-password".toCharArray()));
        assertFalse(storedOne.contains("test-password-1"));
    }

    /**
     * 新建用户的口令文件只能由属主读写。断言失败则本测试失败。
     */
    @Test
    void passwordFileMode600() throws Exception {
        LocalAuthService auth = new LocalAuthService(temp.resolve("home"));
        String login = loginName("mode");
        join(auth.addUser(new AddUser(login, PASSWORD, null, false)));
        assertEquals(OWNER_RW, Files.getPosixFilePermissions(hashFile(temp.resolve("home"), login)));
    }

    /**
     * 不同登录名必须落到家目录下各自的 jasagent 路径，不能共用一个用户目录。断言失败则本测试失败。
     */
    @Test
    void userDirectoryFollowsLoginName() {
        Path home = temp.resolve("home");
        DirectoryLayout layout = new LocalDirectories(home);
        String alpha = loginName("alpha");
        String beta = loginName("beta");
        Path alphaDir = layout.userDirectory(alpha);
        Path betaDir = layout.userDirectory(beta);
        assertNotEquals(alphaDir, betaDir);
        assertEquals(home.resolve(alpha).resolve("jasagent"), alphaDir);
        assertEquals(home.resolve(beta).resolve("jasagent"), betaDir);
    }

    /**
     * 工作区自己的数据目录就是该工作区下的 jasagent 子目录。断言失败则本测试失败。
     */
    @Test
    void workspaceDirectoryUsesJasagent() {
        DirectoryLayout layout = new LocalDirectories(temp.resolve("home"));
        Path workspace = temp.resolve("workspace");
        assertEquals(workspace.resolve("jasagent"), layout.workspaceDirectory(workspace));
    }

    /**
     * 允许列表为空时只接受当前工作目录，选其他目录会得到认证错误。断言失败则本测试失败。
     */
    @Test
    void emptyAllowlistOnlyCurrentDirectory() throws Exception {
        DirectoryLayout layout = new LocalDirectories(temp.resolve("home"));
        Path cwd = temp.resolve("cwd");
        Path other = temp.resolve("other");
        Files.createDirectories(cwd);
        Files.createDirectories(other);
        Path accepted = join(layout.checkWorkspace(cwd, List.of(), cwd));
        assertEquals(cwd.toAbsolutePath().normalize(), accepted);
        AgentException error = assertThrows(AgentException.class,
                () -> join(layout.checkWorkspace(other, List.of(), cwd)));
        assertEquals(ErrorCode.AUTH, error.code());
    }

    /**
     * 候选工作区必须与允许列表中的某一项规范化后相同，列表之外的目录被拒绝。断言失败则本测试失败。
     */
    @Test
    void workspaceOutsideAllowlistRejected() throws Exception {
        DirectoryLayout layout = new LocalDirectories(temp.resolve("home"));
        Path allowed = temp.resolve("allowed");
        Path blocked = temp.resolve("blocked");
        Files.createDirectories(allowed);
        Files.createDirectories(blocked);
        Path accepted = join(layout.checkWorkspace(allowed, List.of(allowed), temp.resolve("cwd")));
        assertEquals(allowed.toAbsolutePath().normalize(), accepted);
        AgentException error = assertThrows(AgentException.class,
                () -> join(layout.checkWorkspace(blocked, List.of(allowed), temp.resolve("cwd"))));
        assertEquals(ErrorCode.AUTH, error.code());
    }

    /**
     * 目录守卫拒绝读取其他用户的口令文件，但允许读取自己的，且结果仍落在自己的 jasagent 目录内。断言失败则本测试失败。
     */
    @Test
    void userCannotReadAnotherUsersDirectory() throws Exception {
        Path home = temp.resolve("home");
        LocalAuthService auth = new LocalAuthService(home);
        String owner = loginName("owner");
        String other = loginName("other");
        join(auth.addUser(new AddUser(owner, PASSWORD, null, false)));
        Session session = join(auth.login(owner, PASSWORD));
        join(auth.addUser(new AddUser(other, PASSWORD, session.token(), true)));
        UserDirectoryGuard guard = new LocalUserDirectoryGuard(new LocalDirectories(home));
        AgentException error = assertThrows(AgentException.class,
                () -> join(guard.resolve(owner, other, Path.of("auth").resolve("password.hash"))));
        assertEquals(ErrorCode.AUTH, error.code());
        Path own = join(guard.resolve(owner, owner, Path.of("auth").resolve("password.hash")));
        assertEquals(home.resolve(owner).resolve("jasagent").resolve("auth").resolve("password.hash").normalize(), own.normalize());
        assertTrue(own.startsWith(home.resolve(owner).resolve("jasagent")));
    }

    /**
     * 登录后的会话文件只有属主可读写，正文里同时记下令牌和登录名。断言失败则本测试失败。
     */
    @Test
    void loginSessionFileMode600() throws Exception {
        Path home = temp.resolve("home");
        LocalAuthService auth = new LocalAuthService(home);
        String login = loginName("session");
        join(auth.addUser(new AddUser(login, PASSWORD, null, false)));
        Session session = join(auth.login(login, PASSWORD));
        Path file = home.resolve(login).resolve("jasagent").resolve("auth").resolve("sessions").resolve(session.sessionId());
        assertTrue(Files.isRegularFile(file));
        assertEquals(OWNER_RW, Files.getPosixFilePermissions(file));
        String body = Files.readString(file);
        assertTrue(body.contains(session.token()));
        assertTrue(body.contains(login));
    }

    /**
     * 注销后原令牌不能再通过认证，对应的会话文件也会被删除。断言失败则本测试失败。
     */
    @Test
    void logoutInvalidatesToken() {
        LocalAuthService auth = new LocalAuthService(temp.resolve("home"));
        String login = loginName("bye");
        join(auth.addUser(new AddUser(login, PASSWORD, null, false)));
        Session session = join(auth.login(login, PASSWORD));
        join(auth.logout(session.token()));
        AgentException error = assertThrows(AgentException.class, () -> join(auth.authenticate(session.token())));
        assertEquals(ErrorCode.AUTH, error.code());
        Path file = temp.resolve("home").resolve(login).resolve("jasagent").resolve("auth").resolve("sessions").resolve(session.sessionId());
        assertFalse(Files.exists(file));
    }

    /**
     * 工作区和用户目录含有同一规则标识时，加载结果采用用户目录的正文，并记录用户目录来源。断言失败则本测试失败。
     */
    @Test
    void sameRuleIdUserWins() throws Exception {
        Path workspace = temp.resolve("ws").resolve("jasagent");
        Path user = temp.resolve("user").resolve("jasagent");
        writeRule(workspace, "base.md", "base", "from-workspace");
        writeRule(user, "base.md", "base", "from-user");
        Catalog catalog = join(new LocalCatalogLoader().load(workspace, user));
        assertEquals("from-user", catalog.rule("base").orElseThrow().body());
        assertTrue(catalog.rule("base").orElseThrow().source().contains(user.toString()));
    }

    /**
     * 同名技能同时存在于工作区和用户目录时，保留用户目录 SKILL.md 的正文。断言失败则本测试失败。
     */
    @Test
    void sameSkillNameUserWins() throws Exception {
        Path workspace = temp.resolve("ws").resolve("jasagent");
        Path user = temp.resolve("user").resolve("jasagent");
        writeSkill(workspace, "research", "from-workspace");
        writeSkill(user, "research", "from-user");
        Catalog catalog = join(new LocalCatalogLoader().load(workspace, user));
        assertEquals("from-user", catalog.skill("research").orElseThrow().body());
    }

    /**
     * 用户目录没有对应规则时，工作区里的规则仍然保留。断言失败则本测试失败。
     */
    @Test
    void workspaceOnlyRuleRemains() throws Exception {
        Path workspace = temp.resolve("ws").resolve("jasagent");
        Path user = temp.resolve("user").resolve("jasagent");
        Files.createDirectories(user.resolve("rules"));
        writeRule(workspace, "extra.md", "extra", "only-workspace");
        Catalog catalog = join(new LocalCatalogLoader().load(workspace, user));
        assertEquals("only-workspace", catalog.rule("extra").orElseThrow().body());
    }

    /**
     * 选定工作区后，返回的会话和再次按令牌读回的会话都记下该工作区的绝对路径。断言失败则本测试失败。
     */
    @Test
    void selectWorkspaceStoredOnSession() throws Exception {
        Path home = temp.resolve("home");
        LocalAuthService auth = new LocalAuthService(home);
        String login = loginName("pick");
        join(auth.addUser(new AddUser(login, PASSWORD, null, false)));
        Session session = join(auth.login(login, PASSWORD));
        Path workspace = temp.resolve("project");
        Files.createDirectories(workspace);
        Session selected = join(auth.selectWorkspace(session.token(), workspace, List.of(workspace), temp.resolve("cwd")));
        assertEquals(workspace.toAbsolutePath().normalize().toString(), selected.workspace());
        Session again = join(auth.authenticate(session.token()));
        assertEquals(workspace.toAbsolutePath().normalize().toString(), again.workspace());
    }

    /**
     * 命令行指针文件只有属主可读写，内容包含登录名和令牌；清除后文件不再存在。断言失败则本测试失败。
     */
    @Test
    void cliPointerMode600() throws Exception {
        Path home = temp.resolve("home");
        LocalAuthService auth = new LocalAuthService(home);
        String login = loginName("cli");
        join(auth.addUser(new AddUser(login, PASSWORD, null, false)));
        Session session = join(auth.login(login, PASSWORD));
        CliSessionPointer pointer = new CliSessionPointer(home);
        join(pointer.remember(session));
        Path file = home.resolve(".jasagent").resolve("cli-session");
        assertEquals(OWNER_RW, Files.getPosixFilePermissions(file));
        String body = Files.readString(file);
        assertTrue(body.contains(login));
        assertTrue(body.contains(session.token()));
        join(pointer.clear());
        assertFalse(Files.exists(file));
    }

    /**
     * 试图用父目录成分当登录名会被认证错误拒绝，并且不会在家目录内外写出逃逸路径上的口令文件。断言失败则本测试失败。
     */
    @Test
    void loginNameRejectsPathSeparators() {
        Path home = temp.resolve("home");
        LocalAuthService auth = new LocalAuthService(home);
        AgentException error = assertThrows(AgentException.class,
                () -> join(auth.addUser(new AddUser("../escape", PASSWORD, null, false))));
        assertEquals(ErrorCode.AUTH, error.code());
        assertFalse(Files.exists(temp.resolve("escape").resolve("jasagent").resolve("auth").resolve("password.hash")));
        assertFalse(Files.exists(home.getParent().resolve("escape").resolve("jasagent").resolve("auth").resolve("password.hash")));
    }

    /**
     * 拼出某个登录名的口令摘要文件位置。
     *
     * @param home 测试用家目录
     * @param login 登录名
     * @return 该用户 jasagent/auth/password.hash 的路径
     */
    private static Path hashFile(Path home, String login) {
        return home.resolve(login).resolve("jasagent").resolve("auth").resolve("password.hash");
    }

    /**
     * 在指定 jasagent 目录下写一条带前言的规则文件，供合并测试读取。副作用是创建 rules 目录并写入该文件。
     *
     * @param jasagentDir 工作区或用户的 jasagent 目录
     * @param fileName 规则文件名
     * @param id 前言中的规则标识
     * @param body 规则正文
     */
    private static void writeRule(Path jasagentDir, String fileName, String id, String body) throws Exception {
        Path file = jasagentDir.resolve("rules").resolve(fileName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nid: " + id + "\ndescription: sample\n---\n" + body + "\n");
    }

    /**
     * 在指定 jasagent 目录下写一个技能的 SKILL.md。副作用是创建技能子目录并写入该文件。
     *
     * @param jasagentDir 工作区或用户的 jasagent 目录
     * @param name 技能目录名，也写入前言
     * @param body 技能正文
     */
    private static void writeSkill(Path jasagentDir, String name, String body) throws Exception {
        Path file = jasagentDir.resolve("skills").resolve(name).resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: " + name + "\ndescription: sample\n---\n" + body + "\n");
    }

    /**
     * 生成带标签和时间的合法登录名，避免多次运行落到同一个用户目录。
     *
     * @param label 短标签，只用于区分用例
     * @return 以字母开头、只含登录名允许字符的名字
     */
    private static String loginName(String label) {
        return "u" + label + HexFormat.of().formatHex(label.getBytes(StandardCharsets.UTF_8)).substring(0, 4)
                + Long.toUnsignedString(System.nanoTime(), 36);
    }

    /**
     * 用测试自己的 PBKDF2 重算，确认摘要算法名、21 万次迭代、16 字节盐和 32 字节密钥都符合约定。不符合时测试失败。
     *
     * @param stored 落盘的摘要串
     * @param password 生成该摘要时使用的口令
     */
    private static void assertPbkdf2(String stored, char[] password) throws Exception {
        String[] parts = stored.split("\\$");
        assertEquals(4, parts.length);
        assertEquals("pbkdf2-sha256", parts[0]);
        assertEquals(210_000, Integer.parseInt(parts[1]));
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] actual = Base64.getDecoder().decode(parts[3]);
        assertEquals(16, salt.length);
        assertEquals(32, actual.length);
        assertArrayEquals(independentPbkdf2(password, salt, 210_000), actual);
    }

    /**
     * 用测试侧独立实现判断摘要是否由给定口令派生，避免测试只调用被测校验方法来自证。
     *
     * @param stored 落盘的摘要串
     * @param password 用来重算的口令
     * @return 独立派生结果与摘要中的密钥一致时为真
     */
    private static boolean matchesIndependent(String stored, char[] password) throws Exception {
        String[] parts = stored.split("\\$");
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] actual = Base64.getDecoder().decode(parts[3]);
        return Arrays.equals(independentPbkdf2(password, salt, Integer.parseInt(parts[1])), actual);
    }

    /**
     * 在测试里单独做 PBKDF2-HMAC-SHA256，并在结束时清掉密钥规格中的口令副本。
     *
     * @param password 参与派生的口令
     * @param salt 从摘要中解出的盐
     * @param iterations 从摘要中读出的迭代次数
     * @return 派生出的密钥字节
     */
    private static byte[] independentPbkdf2(char[] password, byte[] salt, int iterations)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 在限定时间内取回异步结果，并把包装异常还原成原来的运行时异常；等待被中断时重新置上中断标记。
     *
     * @param stage 待等待的异步阶段
     * @return 阶段成功时的结果
     */
    private static <T> T join(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
