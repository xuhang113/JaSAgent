package dev.jasagent.auth;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 网关读取账号角色和租户、以及接受自助注册的入口。
 * 实现把账号写在已有 MySQL 里，不把文件目录当成唯一存储。
 */
public interface AccountDirectory {
    /**
     * 用登录名和口令注册一个普通用户，并为其创建独立租户。不授予 admin，也不加入已有租户。
     *
     * @param login 登录名，规则与 {@code LoginNames} 相同
     * @param password 口令；空数组会被拒绝。调用方负责事后清除
     * @return 完成后得到登录名、角色和租户；登录名无效、口令为空或用户已存在时失败
     */
    CompletionStage<AccountProfile> register(String login, char[] password);

    /**
     * 按登录名读取角色和租户。没有这个账号时失败，说明里不含口令。
     *
     * @param login 登录名
     * @return 账号身份
     */
    AccountProfile profile(String login);

    /**
     * 列出全部账号的租户，供运维查看集群粘性时跨租户汇总。普通用户的请求到不了这一步。
     *
     * @return 租户 id；没有账号时是空列表
     */
    List<String> tenantIds();
}
