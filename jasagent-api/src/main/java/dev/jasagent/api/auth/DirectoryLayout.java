package dev.jasagent.api.auth;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 本地数据目录的布局：家目录按登录名分用户，工作区选择必须落在当前目录或允许列表上。
 */
public interface DirectoryLayout {
    /**
     * 返回存放各用户数据的家目录根。
     *
     * @return 已经规范化的绝对路径
     */
    Path homeRoot();

    /**
     * 返回某个登录名对应的 jasagent 数据目录。
     *
     * @param login 用户登录名，会先按登录名规则校验
     * @return {@code <家目录>/<登录名>/jasagent}
     */
    Path userDirectory(String login);

    /**
     * 返回某个工作区里存放 jasagent 数据的目录。
     *
     * @param workspace 工作区根路径
     * @return {@code <工作区>/jasagent}
     */
    Path workspaceDirectory(Path workspace);

    /**
     * 检查候选工作区是否允许被选中，并返回规范化后的绝对路径。
     *
     * @param candidate 用户提交的工作区，相对路径相对当前工作目录解析
     * @param allowlist 允许的工作区绝对路径；空列表时只允许当前工作目录
     * @param currentWorkingDirectory 解析相对路径和空允许列表时使用的当前工作目录
     * @return 完成后给出选中工作区绝对路径的阶段；候选为空、不在允许列表中时失败
     */
    CompletionStage<Path> checkWorkspace(Path candidate, List<Path> allowlist, Path currentWorkingDirectory);

    /**
     * 把相对路径解析到属主自己的用户目录内。请求者必须就是属主。
     *
     * @param requesterLogin 发起读取的登录名
     * @param ownerLogin 目录属主的登录名，必须与请求者相同
     * @param relative 相对用户目录的路径，必须是相对路径且解析后仍留在该目录内
     * @return 完成后给出规范化绝对路径的阶段；跨用户、绝对路径或逃出目录时失败
     */
    CompletionStage<Path> resolveOwn(String requesterLogin, String ownerLogin, Path relative);
}
