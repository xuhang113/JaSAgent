package dev.jasagent.api.auth;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 本地登录服务。负责建用户、签发和注销会话，以及把已登录会话绑定到允许的工作区。
 */
public interface AuthService {
    /**
     * 创建用户目录和口令摘要。尚无用户时可以直接创建；已有用户时只接受带有效令牌的本机命令。
     *
     * @param request 登录名、口令、调用方令牌，以及这次创建是否来自本机命令
     * @return 完成后给出新建账号的阶段；口令为空、用户已存在或调用方无权创建时失败
     */
    CompletionStage<UserAccount> addUser(AddUser request);

    /**
     * 用登录名和口令创建用户，并只把登录名交回调用方。创建规则与 {@link #addUser(AddUser)} 相同。
     *
     * @param login 新用户登录名
     * @param password 口令，用字符数组传递以便调用方事后清除
     * @param callerToken 已登录调用方的令牌；创建第一个用户时可以不使用
     * @param localCli 为 true 时表示调用来自本机命令，已有用户之后只有这种调用能再建用户
     * @return 完成后给出新建用户登录名的阶段
     */
    default CompletionStage<String> addUser(String login, char[] password, String callerToken, boolean localCli) {
        return addUser(new AddUser(login, password, callerToken, localCli)).thenApply(account -> account.login());
    }

    /**
     * 核对口令并签发新会话。新会话尚未选择工作区。
     *
     * @param login 登录名
     * @param password 口令，用字符数组传递以便调用方事后清除
     * @return 完成后给出会话编号、令牌和登录名的阶段；口令不匹配或用户不存在时失败
     */
    CompletionStage<Session> login(String login, char[] password);

    /**
     * 按令牌找到会话并删除它，使该令牌不能再通过鉴权。
     *
     * @param token 登录时签发的令牌
     * @return 会话文件删除后结束的阶段；令牌为空或找不到会话时失败，正常完成没有业务返回值
     */
    CompletionStage<Void> logout(String token);

    /**
     * 按令牌恢复会话，不改变会话内容。
     *
     * @param token 登录时签发的令牌
     * @return 完成后给出对应会话的阶段；令牌为空或找不到会话时失败
     */
    CompletionStage<Session> authenticate(String token);

    /**
     * 为令牌对应的会话选定工作区，并把选中的绝对路径写回会话。
     *
     * @param token 已登录会话的令牌
     * @param workspace 用户提交的工作区，相对路径相对当前工作目录解析
     * @param allowlist 允许的工作区；空列表时只允许当前工作目录
     * @param currentWorkingDirectory 解析相对路径和空允许列表时使用的当前工作目录
     * @return 完成后给出已写回工作区路径的会话的阶段；工作区不被允许时失败
     */
    CompletionStage<Session> selectWorkspace(String token, Path workspace, List<Path> allowlist, Path currentWorkingDirectory);
}
