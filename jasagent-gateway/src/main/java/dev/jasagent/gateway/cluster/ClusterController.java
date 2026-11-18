package dev.jasagent.gateway.cluster;

import dev.jasagent.auth.AccountDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 控制台的集群接口。数据来自当时的 Nacos 发现和本进程长连接，不向浏览器编造一台静态约定实例。
 * 登录会话和存活探测都在网关进程内，本轮网关按单副本验收。
 */
@RestController
public class ClusterController {
    private final ClusterInventory inventory;
    private final AccountDirectory accounts;
    private final String tenantId;

    /**
     * 注入集群汇总、账号目录和配置里的缺省租户。
     * 非 admin 到不了这里，{@link ClusterAccessFilter} 已经拒绝。
     *
     * @param inventory 按目录组装每一行
     * @param accounts 用来并上每个用户自己的租户
     * @param tenantId 配置里的租户 id，空白时按 local
     */
    public ClusterController(ClusterInventory inventory, AccountDirectory accounts,
                             @Value("${jasagent.gateway.tenant-id:local}") String tenantId) {
        this.inventory = inventory;
        this.accounts = accounts;
        this.tenantId = tenantId == null || tenantId.isBlank() ? "local" : tenantId;
    }

    /**
     * 返回 admin 能看见的 agent。粘性用户覆盖账号目录里的全部租户，再补上配置里的缺省租户。
     * 这只用于集群监控，不因此放开他人的聊天会话。
     *
     * @return 含 agents 数组的正文
     */
    @GetMapping("/api/cluster")
    public Map<String, Object> cluster() {
        LinkedHashSet<String> tenants = new LinkedHashSet<>();
        for (String tenant : accounts.tenantIds()) {
            if (tenant != null && !tenant.isBlank()) {
                tenants.add(tenant);
            }
        }
        tenants.add(tenantId);
        return inventory.snapshotAll(tenants);
    }
}
