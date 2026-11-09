package dev.jasagent.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子 agent 只为结果物开启。
 */
class SubAgentNeedTest {
    /**
     * 问候、能力说明和执行方式问题不另开子 agent。
     */
    @Test
    void chatStaysOnTheMainSession() {
        assertFalse(SubAgentNeed.required("你好"));
        assertFalse(SubAgentNeed.required("你能做什么"));
        assertFalse(SubAgentNeed.required("你是怎么执行的，异步的吗"));
        assertFalse(SubAgentNeed.required("  "));
        assertFalse(SubAgentNeed.required(null));
    }

    /**
     * 要工作区事实或文件结果时才开子 agent。
     */
    @Test
    void workspaceResultOpensASubAgent() {
        assertTrue(SubAgentNeed.required("工作区内有多少文件"));
        assertTrue(SubAgentNeed.required("读一下 pom.xml"));
        assertTrue(SubAgentNeed.required("在仓库里搜索 TODO"));
    }
}
