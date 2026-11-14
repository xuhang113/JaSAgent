package dev.jasagent.app.nacos;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程就绪后向 Nacos 注册，退出时注销。注册地址必须是网关能访问的非回环地址。
 * 没有配置 Nacos 时什么都不登记，避免把 127.0.0.1 写进注册中心。
 */
public final class NacosAgentRegistration implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {
    private final NacosNaming naming;
    private final NacosSettings settings;
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private volatile String ip = "";
    private volatile int port;

    /**
     * 绑定命名操作和连接参数。此时还不注册，要等进程就绪。
     *
     * @param naming 注册与注销
     * @param settings 地址、服务名和监听地址
     */
    public NacosAgentRegistration(NacosNaming naming, NacosSettings settings) {
        this.naming = naming;
        this.settings = settings;
    }

    /**
     * 应用就绪后注册。端口取当前 Web 服务器的实际端口，而不是写死的约定端口。
     * 副作用：向 Nacos 登记一台临时实例。
     *
     * @param event 就绪事件
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!settings.configured()) {
            return;
        }
        int actual = 0;
        if (event.getApplicationContext() instanceof WebServerApplicationContext web && web.getWebServer() != null) {
            actual = web.getWebServer().getPort();
        }
        registerReady(actual);
    }

    /**
     * 用给定端口完成注册。监听地址若只是回环，则拒绝，不把不可达地址发出去。
     * 副作用：成功后 {@link #registered()} 为真。
     *
     * @param actualPort 进程正在监听的端口
     */
    public void registerReady(int actualPort) {
        if (!settings.configured()) {
            return;
        }
        if (ReachableAddress.loopbackBind(settings.bindAddress())) {
            throw new AgentException(ErrorCode.INTERNAL, ReachableAddress.LOOPBACK_BIND);
        }
        if (actualPort < 1 || actualPort > 65535) {
            throw new AgentException(ErrorCode.INTERNAL, "agent 端口无效");
        }
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        String host = ReachableAddress.require(settings.advertiseHost());
        String instanceId = NacosInstanceIds.of(host, actualPort);
        try {
            naming.register(settings.service(), settings.group(), instanceId, host, actualPort);
        } catch (RuntimeException ex) {
            registered.set(false);
            throw ex;
        }
        this.ip = host;
        this.port = actualPort;
    }

    /**
     * 进程关闭时注销并停掉心跳。重复调用只有第一次会联系 Nacos。
     * 副作用：发现结果里不再保留这台实例。
     */
    @Override
    public void destroy() {
        deregister();
    }

    /**
     * 注销当前实例。还没注册成功时直接返回。
     * 副作用：调用命名操作的注销和关闭。
     */
    public void deregister() {
        if (!registered.compareAndSet(true, false)) {
            return;
        }
        RuntimeException failure = null;
        try {
            naming.deregister(settings.service(), settings.group(), ip, port);
        } catch (RuntimeException ex) {
            failure = ex;
        }
        try {
            naming.shutdown();
        } catch (RuntimeException ex) {
            if (failure == null) {
                failure = ex;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 当前进程是否已经向 Nacos 登记并且还没注销。
     *
     * @return 已登记时为真
     */
    public boolean registered() {
        return registered.get();
    }
}
