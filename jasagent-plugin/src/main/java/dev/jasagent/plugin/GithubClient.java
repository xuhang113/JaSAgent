package dev.jasagent.plugin;

/**
 * 查询 GitHub 仓库的默认分支。
 * 工具用它隔开网络，测试可以直接返回分支名。
 */
public interface GithubClient {
    /**
     * 查询一个仓库现在的默认分支。
     *
     * @param owner 仓库所有者
     * @param name 仓库名，不含所有者
     * @return 默认分支名；查不到时可以返回空串或空引用，工具会把它当成错误
     */
    String defaultBranch(String owner, String name);
}
