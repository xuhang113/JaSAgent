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

class LoginDirectoryTest {
    private static final char[] PASSWORD = "test-password-1".toCharArray();
    private static final Set<PosixFilePermission> OWNER_RW = Set.of(OWNER_READ, OWNER_WRITE);

    @TempDir
    Path temp;

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

    @Test
    void passwordFileMode600() throws Exception {
        LocalAuthService auth = new LocalAuthService(temp.resolve("home"));
        String login = loginName("mode");
        join(auth.addUser(new AddUser(login, PASSWORD, null, false)));
        assertEquals(OWNER_RW, Files.getPosixFilePermissions(hashFile(temp.resolve("home"), login)));
    }

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

    @Test
    void workspaceDirectoryUsesJasagent() {
        DirectoryLayout layout = new LocalDirectories(temp.resolve("home"));
        Path workspace = temp.resolve("workspace");
        assertEquals(workspace.resolve("jasagent"), layout.workspaceDirectory(workspace));
    }

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

    @Test
    void sameSkillNameUserWins() throws Exception {
        Path workspace = temp.resolve("ws").resolve("jasagent");
        Path user = temp.resolve("user").resolve("jasagent");
        writeSkill(workspace, "research", "from-workspace");
        writeSkill(user, "research", "from-user");
        Catalog catalog = join(new LocalCatalogLoader().load(workspace, user));
        assertEquals("from-user", catalog.skill("research").orElseThrow().body());
    }

    @Test
    void workspaceOnlyRuleRemains() throws Exception {
        Path workspace = temp.resolve("ws").resolve("jasagent");
        Path user = temp.resolve("user").resolve("jasagent");
        Files.createDirectories(user.resolve("rules"));
        writeRule(workspace, "extra.md", "extra", "only-workspace");
        Catalog catalog = join(new LocalCatalogLoader().load(workspace, user));
        assertEquals("only-workspace", catalog.rule("extra").orElseThrow().body());
    }

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

    private static Path hashFile(Path home, String login) {
        return home.resolve(login).resolve("jasagent").resolve("auth").resolve("password.hash");
    }

    private static void writeRule(Path jasagentDir, String fileName, String id, String body) throws Exception {
        Path file = jasagentDir.resolve("rules").resolve(fileName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nid: " + id + "\ndescription: sample\n---\n" + body + "\n");
    }

    private static void writeSkill(Path jasagentDir, String name, String body) throws Exception {
        Path file = jasagentDir.resolve("skills").resolve(name).resolve("SKILL.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: " + name + "\ndescription: sample\n---\n" + body + "\n");
    }

    private static String loginName(String label) {
        return "u" + label + HexFormat.of().formatHex(label.getBytes(StandardCharsets.UTF_8)).substring(0, 4)
                + Long.toUnsignedString(System.nanoTime(), 36);
    }

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

    private static boolean matchesIndependent(String stored, char[] password) throws Exception {
        String[] parts = stored.split("\\$");
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] actual = Base64.getDecoder().decode(parts[3]);
        return Arrays.equals(independentPbkdf2(password, salt, Integer.parseInt(parts[1])), actual);
    }

    private static byte[] independentPbkdf2(char[] password, byte[] salt, int iterations)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

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
