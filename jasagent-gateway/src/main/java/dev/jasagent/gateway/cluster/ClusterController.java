package dev.jasagent.gateway.cluster;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 控制台的集群接口。数据来自长连接和粘性库，不向浏览器编造一台不在目录里的 agent。
 */
@RestController
public class ClusterController {
    private final ClusterInventory inventory;
    private final String tenantId;

    /**
     * 注入集群汇总和本网关的租户。
     *
     * @param inventory 按目录组装每一行
     * @param tenantId 配置里的租户 id，空白时按 local
     */
    public ClusterController(ClusterInventory inventory, @Value("${jasagent.gateway.tenant-id:local}") String tenantId) {
        this.inventory = inventory;
        this.tenantId = tenantId == null || tenantId.isBlank() ? "local" : tenantId;
    }

    /**
     * 返回当前租户能看见的 agent。调用前会话过滤已经要求登录。
     *
     * @return 含 agents 数组的正文
     */
    @GetMapping("/api/cluster")
    public Map<String, Object> cluster() {
        return inventory.snapshot(tenantId);
    }
}
