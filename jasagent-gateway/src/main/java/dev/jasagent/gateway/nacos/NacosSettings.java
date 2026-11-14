package dev.jasagent.gateway.nacos;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 网关查询 Nacos 时使用的连接参数。地址和口令只来自环境或仓库外文件。
 * {@link #toString()} 不包含地址、账号和口令。
 */
public final class NacosSettings {
    /**
     * 与 agent 注册时相同的分组。
     */
    public static final String DEFAULT_GROUP = "DEFAULT_GROUP";
    /**
     * 与 agent 注册时相同的服务名。
     */
    public static final String DEFAULT_SERVICE = "jasagent-agent";
    /**
     * 实例元数据里的稳定编号，和 agent 写入的键相同。
     */
    public static final String META_INSTANCE_ID = "instanceId";

    private final String serverAddr;
    private final String username;
    private final String password;
    private final String namespace;
    private final String group;
    private final String service;

    /**
     * 保存已经解析好的连接参数。
     *
     * @param serverAddr Nacos 地址
     * @param username 账号
     * @param password 口令
     * @param namespace 命名空间
     * @param group 分组
     * @param service 服务名
     */
    public NacosSettings(String serverAddr, String username, String password, String namespace, String group, String service) {
        this.serverAddr = serverAddr == null ? "" : serverAddr.trim();
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.namespace = namespace == null ? "" : namespace.trim();
        this.group = group == null || group.isBlank() ? DEFAULT_GROUP : group.trim();
        this.service = service == null || service.isBlank() ? DEFAULT_SERVICE : service.trim();
    }

    /**
     * 从配置值组装参数。账号非空却读不到口令文件时拒绝，说明里不带地址和口令。
     *
     * @param serverAddr Nacos 地址
     * @param username 账号
     * @param passwordFile 口令文件
     * @param namespace 命名空间
     * @param group 分组
     * @param service 服务名
     * @return 连接参数
     */
    public static NacosSettings load(String serverAddr, String username, String passwordFile, String namespace,
                                     String group, String service) {
        String user = username == null ? "" : username.trim();
        String password = readSecret(passwordFile);
        if (!user.isEmpty() && password == null) {
            throw new AgentException(ErrorCode.INTERNAL, "Nacos 口令文件不可用");
        }
        if (password == null) {
            password = "";
        }
        return new NacosSettings(serverAddr, user, password, namespace, group, service);
    }

    /**
     * 是否配置了 Nacos 地址。没有地址时发现直接失败，不回退静态目录。
     *
     * @return 地址非空时为真
     */
    public boolean configured() {
        return !serverAddr.isEmpty();
    }

    /**
     * Nacos 地址。只交给客户端。
     *
     * @return 地址
     */
    public String serverAddr() {
        return serverAddr;
    }

    /**
     * 认证账号。
     *
     * @return 账号，可以是空串
     */
    public String username() {
        return username;
    }

    /**
     * 认证口令。调用方不得打印。
     *
     * @return 口令，没有时是空串
     */
    public String password() {
        return password;
    }

    /**
     * 命名空间。空白表示公共命名空间。
     *
     * @return 命名空间
     */
    public String namespace() {
        return namespace;
    }

    /**
     * 查询使用的分组。
     *
     * @return 分组名
     */
    public String group() {
        return group;
    }

    /**
     * 查询使用的服务名。
     *
     * @return 服务名
     */
    public String service() {
        return service;
    }

    /**
     * 返回固定文本，避免地址和口令进到日志。
     *
     * @return 固定文本
     */
    @Override
    public String toString() {
        return "NacosSettings";
    }

    /**
     * 读取口令文件第一行。没有文件时返回 null，不把内容放进异常。
     *
     * @param passwordFile 仓库外文件路径
     * @return 口令；没有文件时是 null
     */
    private static String readSecret(String passwordFile) {
        if (passwordFile == null || passwordFile.isBlank()) {
            return null;
        }
        Path file = Path.of(passwordFile.trim());
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String line = Files.readString(file, StandardCharsets.UTF_8);
            int newline = line.indexOf('\n');
            if (newline >= 0) {
                line = line.substring(0, newline);
            }
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            String value = line.trim();
            return value.isEmpty() ? null : value;
        } catch (IOException ex) {
            return null;
        }
    }
}
