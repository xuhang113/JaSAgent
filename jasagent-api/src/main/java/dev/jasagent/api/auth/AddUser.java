package dev.jasagent.api.auth;

/**
 * 创建本地用户所需的材料。口令用字符数组传递，便于调用方在用完后清除。
 *
 * @param login 新用户登录名
 * @param password 口令；空数组会被拒绝
 * @param callerToken 已登录调用方的令牌。创建第一个用户时可以不使用，之后必须能对上一个有效会话
 * @param localCli 为 true 时表示这次创建来自本机命令。已有用户之后，只有本机命令能再建用户
 */
public record AddUser(String login, char[] password, String callerToken, boolean localCli) {
}
