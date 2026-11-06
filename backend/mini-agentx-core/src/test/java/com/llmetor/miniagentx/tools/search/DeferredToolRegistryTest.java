package com.llmetor.miniagentx.tools.search;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tool_search 三件套单测（M7-A 组内核侧）：KEYWORD 打分发现 / 每请求 Session 隔离与累积 /
 * 重名注册 warn 保留先注册者（缺陷清单项）/ tool_search 工具返回定义文本。
 */
class DeferredToolRegistryTest {

    // ==================== 演示延迟工具 ====================

    static class SqlTools {
        @Tool(name = "executeSql", description = "执行只读 SELECT 查询并返回结果数据，查数据库用")
        public String executeSql(String sql) {
            return "ok";
        }

        @Tool(name = "listTables", description = "列出当前数据库的全部表和视图，挑相关表")
        public String listTables() {
            return "ok";
        }

        @Tool(name = "currentTime", description = "获取当前时间，锚定相对时间词")
        public String currentTime() {
            return "ok";
        }
    }

    static class SqlToolsV2 {
        @Tool(name = "executeSql", description = "第二版执行查询（重名注册应被忽略）")
        public String executeSql(String sql) {
            return "v2";
        }
    }

    static class ChartTools {
        @Tool(name = "generate_chart", description = "生成柱状图/饼图/折线图等图表并返回图片 URL")
        public String generateChart(String type) {
            return "ok";
        }
    }

    private static DeferredToolRegistry registry() {
        DeferredToolRegistry r = new DeferredToolRegistry(new ToolSearchConfig(ToolSearchConfig.Mode.KEYWORD, 5));
        r.registerAll(List.of(ToolCallbacks.from(new SqlTools())));
        r.registerAll(List.of(ToolCallbacks.from(new ChartTools())));
        return r;
    }

    // ==================== 注册表行为 ====================

    @Test
    void duplicateRegistrationKeepsFirstAndDoesNotGrow() {
        DeferredToolRegistry r = registry();
        int before = r.size();
        r.registerAll(List.of(ToolCallbacks.from(new SqlToolsV2()))); // executeSql 重名
        assertEquals(before, r.size(), "重名注册不应增加目录");
        // 保留先注册者：description 仍是第一版
        assertEquals("执行只读 SELECT 查询并返回结果数据，查数据库用",
                r.get("executeSql").getToolDefinition().description());
    }

    @Test
    void unregisterRemovesFromCatalogAndDiscovery() {
        DeferredToolRegistry r = registry();
        DeferredToolRegistry.Session session = r.newSession();
        session.search("SQL 查询 数据库 表");
        assertTrue(session.isDiscovered("executeSql"), "前置：已发现 executeSql");

        // 反注册后：目录取不到、新搜索不再命中
        assertTrue(r.unregister("executeSql"), "存在时应返回 true");
        assertFalse(r.unregister("executeSql"), "重复反注册返回 false");
        assertNull(r.get("executeSql"), "目录中已移除");
        assertFalse(r.newSession().searchOnly("SQL 查询 数据库 表").stream()
                .anyMatch(h -> h.name().equals("executeSql")), "新搜索不应再命中已反注册工具");
    }

    @Test
    void unregisterAllReturnsRemovedCountAndSupportsReRegister() {
        DeferredToolRegistry r = registry();
        int before = r.size();
        assertEquals(2, r.unregisterAll(List.of("executeSql", "generate_chart", "notExist")),
                "实际移除 2 个（notExist 不计）");
        assertEquals(before - 2, r.size());

        // MCP 重建场景：反注册旧工具后重新注册同名新实例
        r.registerAll(List.of(ToolCallbacks.from(new SqlToolsV2())));
        assertEquals(before - 2 + 1, r.size());
        assertEquals("第二版执行查询（重名注册应被忽略）",
                r.get("executeSql").getToolDefinition().description(),
                "反注册后再注册应以新实例生效（不受 putIfAbsent 影响）");
    }

    @Test
    void sessionDiscoveryAccumulatesAcrossSearchesWithinRequest() {
        DeferredToolRegistry r = registry();
        DeferredToolRegistry.Session session = r.newSession();

        assertTrue(session.discoveredTools().isEmpty(), "初始零发现");
        session.search("SQL 查询 数据库 表");
        int afterFirst = session.discoveredTools().size();
        assertTrue(afterFirst > 0, "首次搜索应发现 SQL 工具");
        session.search("图表 生成");
        assertTrue(session.discoveredTools().size() >= afterFirst, "发现跨搜索累积");
        assertTrue(session.isDiscovered("generate_chart"));
    }

    @Test
    void sessionsAreIsolatedBetweenRequests() {
        DeferredToolRegistry r = registry();
        DeferredToolRegistry.Session s1 = r.newSession();
        s1.search("SQL 查询 数据库 表");
        assertFalse(s1.discoveredTools().isEmpty());

        DeferredToolRegistry.Session s2 = r.newSession();
        assertTrue(s2.discoveredTools().isEmpty(), "新请求 Session 应从零开始");
        assertFalse(s2.isDiscovered("executeSql"));
    }

    // ==================== KEYWORD 打分 ====================

    @Test
    void chineseQueryScoresSqlToolsAboveChartTool() {
        DeferredToolRegistry.Session session = registry().newSession();
        List<KeywordToolSearcher.SearchHit> hits = session.searchOnly("SQL 查询 数据库 表 统计");

        assertFalse(hits.isEmpty());
        assertTrue(hits.size() <= 5, "不超过 maxResults");
        // executeSql / listTables 应在结果里，图表工具不该进（中文数据 query 不含图表词）
        List<String> names = hits.stream().map(KeywordToolSearcher.SearchHit::name).toList();
        assertTrue(names.contains("executeSql"), "命中应含 executeSql: " + names);
        assertTrue(names.contains("listTables"), "命中应含 listTables: " + names);
        assertFalse(names.contains("generate_chart"), "图表工具不应被数据 query 命中: " + names);
    }

    @Test
    void exactNameQueryScoresHighest() {
        DeferredToolRegistry.Session session = registry().newSession();
        List<KeywordToolSearcher.SearchHit> hits = session.searchOnly("executeSql");
        assertEquals("executeSql", hits.get(0).name(), "精确名查询应排第一");
        assertTrue(hits.get(0).score() >= 100, "精确名至少 100 分");
    }

    @Test
    void unrelatedQueryReturnsEmpty() {
        DeferredToolRegistry.Session session = registry().newSession();
        List<KeywordToolSearcher.SearchHit> hits = session.searchOnly("天气预报 周末 出游");
        assertTrue(hits.isEmpty(), "无关 query 不应硬凑命中");
    }

    @Test
    void maxResultsCapsHitCount() {
        DeferredToolRegistry small = new DeferredToolRegistry(new ToolSearchConfig(ToolSearchConfig.Mode.KEYWORD, 1));
        small.registerAll(List.of(ToolCallbacks.from(new SqlTools())));
        List<KeywordToolSearcher.SearchHit> hits = small.newSession().searchOnly("SQL 查询 数据库 表 当前时间");
        assertEquals(1, hits.size(), "maxResults=1 时只返回 1 个");
    }

    // ==================== tool_search 工具 ====================

    @Test
    void toolSearchReturnsDefinitionsAndRegistersDiscovery() {
        DeferredToolRegistry r = registry();
        DeferredToolRegistry.Session session = r.newSession();
        ToolCallback tool = ToolSearchTool.buildOrNull(r, session);

        assertTrue(tool != null, "目录非空时应构建 tool_search");
        assertEquals("tool_search", tool.getToolDefinition().name());

        String out = tool.call("{\"query\":\"SQL 查询 数据库 表\"}");
        assertTrue(out.contains("已发现"), out);
        assertTrue(out.contains("executeSql"), "结果应含工具定义: " + out);
        assertTrue(out.contains("参数"), "结果应含参数 Schema 提示");
        // 调用即登记 → session 可见
        assertTrue(session.isDiscovered("executeSql"));
        assertFalse(session.discoveredTools().isEmpty());
    }

    @Test
    void toolSearchNoMatchGivesRetryGuidance() {
        DeferredToolRegistry r = registry();
        DeferredToolRegistry.Session session = r.newSession();
        ToolCallback tool = ToolSearchTool.buildOrNull(r, session);
        String out = tool.call("{\"query\":\"天气预报\"}");
        assertTrue(out.contains("没有匹配"), out);
        assertTrue(out.contains("换一组关键词"), "应引导换词重试");
    }

    @Test
    void emptyRegistryBuildsNothing() {
        DeferredToolRegistry empty = new DeferredToolRegistry();
        assertNull(ToolSearchTool.buildOrNull(empty, empty.newSession()),
                "空目录不应注册 tool_search（省一个无意义工具）");
    }

    @Test
    void nullQueryGetsParamHint() {
        DeferredToolRegistry r = registry();
        ToolCallback tool = ToolSearchTool.buildOrNull(r, r.newSession());
        String out = tool.call("{}");
        assertTrue(out.contains("参数错误"), out);
    }

    @Test
    void configDefaultsAreKeywordFive() {
        ToolSearchConfig config = ToolSearchConfig.keywordDefault();
        assertEquals(ToolSearchConfig.Mode.KEYWORD, config.mode());
        assertEquals(5, config.maxResults());
        // 非法 maxResults 回落默认 5
        assertEquals(5, new ToolSearchConfig(ToolSearchConfig.Mode.KEYWORD, 0).maxResults());
    }
}
