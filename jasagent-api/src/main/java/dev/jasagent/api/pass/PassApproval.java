package dev.jasagent.api.pass;

import java.nio.file.Path;

/**
 * 用户对一张等待中的通行证作出的决定。门用它核对用户、执行和通行证编号后再改决定。
 *
 * @param userId 作出决定的用户登录名，必须与通行证上的用户一致
 * @param executionId 通行证所属的执行编号
 * @param passId 通行证编号
 * @param decision 用户决定，处理时只接受 ALLOW 或 DENY
 * @param userDirectory 该用户的 jasagent 目录，用来找到挂起的通行证文件
 */
public record PassApproval(
        String userId,
        String executionId,
        String passId,
        PassDecision decision,
        Path userDirectory) {
}
