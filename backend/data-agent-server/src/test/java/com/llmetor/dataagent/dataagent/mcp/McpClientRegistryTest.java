package com.llmetor.dataagent.dataagent.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 客户端运行时注册表单测（M6 补充）：version 随增删递增（DodoAgent 热同步信号）、
 * 按注入方式过滤、clear 全量回收。client 参数传 null（注册表只存不调，无需真客户端）。
 */
class McpClientRegistryTest {

    static class AlwaysTools {
        @Tool(name = "dummy_always_a", description = "a")
        public String a() {
            return "ok";
        }
    }

    static class OnlineTools {
        @Tool(name = "dummy_online_b", description = "b")
        public String b() {
            return "ok";
        }
    }

    @Test
    void putBumpsVersionAndReturnsReplacedOldEntry() {
        McpClientRegistry registry = new McpClientRegistry();
        long v0 = registry.version();

        assertNull(registry.put("s1", null, new ToolCallback[0], McpServerManager.INJECT_ALWAYS),
                "首放无旧实例");
        assertEquals(v0 + 1, registry.version(), "put 递增 version");

        McpClientRegistry.Entry old = registry.put("s1", null, new ToolCallback[0],
                McpServerManager.INJECT_ALWAYS);
        assertEquals("s1", old.serverName(), "替换返回被顶掉的旧实例（调用方负责 close）");
        assertEquals(v0 + 2, registry.version());
    }

    @Test
    void removeBumpsVersionOnlyWhenPresent() {
        McpClientRegistry registry = new McpClientRegistry();
        registry.put("s1", null, new ToolCallback[0], McpServerManager.INJECT_ALWAYS);
        long v = registry.version();

        assertNull(registry.remove("not-exist"), "移除不存在返回 null");
        assertEquals(v, registry.version(), "移除不存在不递增 version");

        assertEquals("s1", registry.remove("s1").serverName(), "移除返回被移除实例");
        assertEquals(v + 1, registry.version(), "实际移除才递增");
    }

    @Test
    void toolsOfInjectModeFiltersBySnapshot() {
        McpClientRegistry registry = new McpClientRegistry();
        registry.put("always-1", null, ToolCallbacks.from(new AlwaysTools()), McpServerManager.INJECT_ALWAYS);
        registry.put("online-1", null, ToolCallbacks.from(new OnlineTools()), McpServerManager.INJECT_ONLINE_TOGGLE);

        assertEquals(1, registry.toolsOfInjectMode(McpServerManager.INJECT_ALWAYS).size());
        assertEquals("dummy_always_a",
                registry.toolsOfInjectMode(McpServerManager.INJECT_ALWAYS).get(0).getToolDefinition().name());
        assertEquals(1, registry.toolsOfInjectMode(McpServerManager.INJECT_ONLINE_TOGGLE).size());
        assertEquals("dummy_online_b",
                registry.toolsOfInjectMode(McpServerManager.INJECT_ONLINE_TOGGLE).get(0).getToolDefinition().name());
    }

    @Test
    void clearReturnsAllClientsForShutdown() {
        McpClientRegistry registry = new McpClientRegistry();
        registry.put("s1", null, new ToolCallback[0], McpServerManager.INJECT_ALWAYS);
        registry.put("s2", null, new ToolCallback[0], McpServerManager.INJECT_ALWAYS);

        assertEquals(2, registry.clear().size(), "@PreDestroy 全量回收客户端");
        assertEquals(0, registry.all().size());
        assertTrue(registry.version() > 0);
    }
}
