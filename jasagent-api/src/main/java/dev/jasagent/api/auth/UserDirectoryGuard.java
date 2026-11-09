package dev.jasagent.api.auth;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/**
 * 用户目录边界。只允许用户解析自己目录下的相对路径，挡住跨用户读取和逃出目录的路径。
 */
public interface UserDirectoryGuard {
    /**
     * 把相对路径解析到属主的用户目录内，并确认请求者就是属主。
     *
     * @param requesterLogin 发起读取的登录名
     * @param ownerLogin 目录属主的登录名，必须与请求者相同
     * @param relative 相对用户目录的路径，必须是相对路径且解析后仍留在该目录内
     * @return 完成后给出规范化绝对路径的阶段；请求者与属主不同、路径为绝对路径或逃出目录时失败
     */
    CompletionStage<Path> resolve(String requesterLogin, String ownerLogin, Path relative);
}
