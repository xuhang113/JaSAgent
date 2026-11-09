package dev.jasagent.api.auth;

import java.nio.file.Path;

/**
 * 一个已经建好的本地用户，登录名对应家目录下的 jasagent 数据目录。
 *
 * @param login 规范化后的登录名
 * @param directory 该用户的 jasagent 目录，其下有口令、会话、规则和技能
 */
public record UserAccount(String login, Path directory) {
}
