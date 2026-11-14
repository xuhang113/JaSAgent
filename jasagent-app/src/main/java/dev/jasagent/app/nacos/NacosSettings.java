package dev.jasagent.app.nacos;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Nacos 连接参数。地址和口令只从调用方已经读到的环境或仓库外文件来，本对象不写日志。
 * {@link #toString()} 不包含地址、账号和口令。
 */
public final class NacosSettings {
    /**
     * 注册和发现使用的分组。agent 与网关必须一致。
     */
    public static final String DEFAULT_GROUP = "DEFAULT_GROUP";
    /**
     * 注册和发现使用的服务名。agent 与网关必须一致。
     */
    public static final String DEFAULT_SERVICE = "jasagent-agent";
    /**
     * 实例元数据里的稳定编号。网关优先读这个字段做粘性。
     */
    public static final String META_INSTANCE_ID = "instanceId";

    private final String serverAddr;
    private final String username;
    private final String password;
    private final String namespace;
    private final String group;
    private final String service;
    private final String advertiseHost;
    private final String bindAddress;

    /**
     * 保存已经解析好的连接参数。口令只留在字段里，不进入异常文本。
     *
     * @param serverAddr Nacos 地址，可以是空白
     * @param username 账号，认证关闭时是空白
     * @param password 口令，认证关闭时是空白
     * @param namespace 命名空间，公共命名空间是空白
     * @param group 分组
     * @param service 服务名
     * @param advertiseHost 要注册的主机，空白时再自动选择
     * @param bindAddress 进程监听地址，空白表示监听所有网卡
     */
    public NacosSettings(String serverAddr, String username, String password, String namespace, String group,
                         String service, String advertiseHost, String bindAddress) {
        this.serverAddr = serverAddr == null ? "" : serverAddr.trim();
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.namespace = namespace == null ? "" : namespace.trim();
        this.group = group == null || group.isBlank() ? DEFAULT_GROUP : group.trim();
        this.service = service == null || service.isBlank() ? DEFAULT_SERVICE : service.trim();
        this.advertiseHost = advertiseHost == null ? "" : advertiseHost.trim();
        this.bindAddress = bindAddress == null ? "" : bindAddress.trim();
    }

    /**
     * 从配置值组装参数。账号非空时必须能读到仓库外的口令文件，否则拒绝。
     * 失败说明里没有地址、账号或口令。
     *
     * @param serverAddr Nacos 地址
     * @param username 账号
     * @param passwordFile 口令文件路径，可以是空白
     * @param namespace 命名空间
     * @param group 分组
     * @param service 服务名
     * @param advertiseHost 注册主机
     * @param bindAddress 监听地址
     * @return 连接参数
     */
    public static NacosSettings load(String serverAddr, String username, String passwordFile, String namespace,
                                     String group, String service, String advertiseHost, String bindAddress) {
        String user = username == null ? "" : username.trim();
        String password = readSecret(passwordFile);
        if (!user.isEmpty() && password == null) {
            throw new AgentException(ErrorCode.INTERNAL, "Nacos 口令文件不可用");
        }
        if (password == null) {
            password = "";
        }
        return new NacosSettings(serverAddr, user, password, namespace, group, service, advertiseHost, bindAddress);
    }

    /**
     * 是否配置了 Nacos 地址。没有地址时不注册，也不连接客户端。
     *
     * @return 地址非空时为真
     */
    public boolean configured() {
        return !serverAddr.isEmpty();
    }

    /**
     * Nacos 地址。只交给客户端，不写入日志。
     *
     * @return 地址，未配置时是空串
     */
    public String serverAddr() {
        return serverAddr;
    }

    /**
     * 认证账号。认证关闭时是空串。
     *
     * @return 账号
     */
    public String username() {
        return username;
    }

    /**
     * 认证口令。只交给客户端，调用方不得打印。
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
     * 服务分组。
     *
     * @return 分组名
     */
    public String group() {
        return group;
    }

    /**
     * 注册使用的服务名。
     *
     * @return 服务名
     */
    public String service() {
        return service;
    }

    /**
     * 调用方指定的注册主机。空白时由 {@link ReachableAddress} 选择非回环地址。
     *
     * @return 主机，可以是空串
     */
    public String advertiseHost() {
        return advertiseHost;
    }

    /**
     * 进程实际监听的地址。空白或 0.0.0.0 表示不是只绑回环。
     *
     * @return 监听地址
     */
    public String bindAddress() {
        return bindAddress;
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
     * 读取口令文件的第一行。文件不存在或读失败时返回 null，不把内容放进异常。
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
