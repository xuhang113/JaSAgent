package dev.jasagent.app.nacos;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

/**
 * 选出网关能直接访问的注册地址。回环、未指定地址和解析失败都不能拿去注册。
 * 失败说明不包含主机名。
 */
public final class ReachableAddress {
    /**
     * 注册地址是回环或未指定地址时的说明。
     */
    public static final String REJECTED = "注册地址不能是回环地址";
    /**
     * 找不到可用网卡时的说明。
     */
    public static final String MISSING = "没有网关能访问的非回环地址";
    /**
     * 进程只监听回环，却还要向 Nacos 注册时的说明。
     */
    public static final String LOOPBACK_BIND = "进程只监听回环地址，不能向 Nacos 注册";

    /**
     * 禁止实例化。地址只通过静态方法选择。
     */
    private ReachableAddress() {
    }

    /**
     * 使用配置的主机；空白时自动选择一块非回环 IPv4。结果必须能被网关访问。
     *
     * @param configured 配置的主机，可以是空白
     * @return 非回环主机
     */
    public static String require(String configured) {
        String chosen = configured == null ? "" : configured.trim();
        if (chosen.isEmpty()) {
            chosen = detect();
        }
        if (!usable(chosen)) {
            throw new AgentException(ErrorCode.INTERNAL, REJECTED);
        }
        return chosen;
    }

    /**
     * 判断这个监听地址是不是只绑在回环上。空白和 0.0.0.0 表示监听所有网卡，不算回环绑定。
     *
     * @param bind {@code server.address}，可以是空白
     * @return 只监听回环时为真
     */
    public static boolean loopbackBind(String bind) {
        if (bind == null || bind.isBlank()) {
            return false;
        }
        String text = strip(bind);
        if ("0.0.0.0".equals(text) || "::".equals(text)) {
            return false;
        }
        return !usable(text);
    }

    /**
     * 判断主机能不能当作注册地址。回环、localhost、0.0.0.0 和解析不出的名字都拒绝。
     *
     * @param host 主机
     * @return 可以注册时为真
     */
    public static boolean usable(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String text = strip(host);
        if ("localhost".equals(text) || "localhost.localdomain".equals(text) || "::1".equals(text)
                || "0.0.0.0".equals(text) || "::".equals(text)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(text);
            return !address.isLoopbackAddress() && !address.isAnyLocalAddress() && !address.isLinkLocalAddress();
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    /**
     * 从已启用的网卡里选一个站点本地 IPv4；没有站点本地地址时用第一块公网 IPv4。
     *
     * @return 非回环 IPv4
     */
    public static String detect() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            if (nics == null) {
                throw new AgentException(ErrorCode.INTERNAL, MISSING);
            }
            String fallback = null;
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isAnyLocalAddress()
                            || address.isLinkLocalAddress() || address.isMulticastAddress()) {
                        continue;
                    }
                    String ip = address.getHostAddress();
                    if (address.isSiteLocalAddress()) {
                        return ip;
                    }
                    if (fallback == null) {
                        fallback = ip;
                    }
                }
            }
            if (fallback != null) {
                return fallback;
            }
        } catch (SocketException ex) {
            throw new AgentException(ErrorCode.INTERNAL, MISSING);
        }
        throw new AgentException(ErrorCode.INTERNAL, MISSING);
    }

    /**
     * 去掉首尾空白、方括号，并转成小写，便于比较 localhost 和 IPv6。
     *
     * @param host 原始主机
     * @return 规范化后的文本
     */
    private static String strip(String host) {
        String text = host.trim().toLowerCase();
        if (text.startsWith("[") && text.endsWith("]") && text.length() > 2) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
