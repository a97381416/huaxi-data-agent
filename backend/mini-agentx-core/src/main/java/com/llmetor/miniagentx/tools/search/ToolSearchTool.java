package com.llmetor.miniagentx.tools.search;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.function.Function;

/**
 * 工具发现工具（工具名 {@code tool_search}，docs/02 §7 三件套之一）。
 *
 * <p>初始工具清单只含常驻集 + 本工具；LLM 需要清单外的能力（如数据查询、图表）
 * 时，用业务关键词调本工具 → KEYWORD 打分取 Top maxResults → 登记进本请求的
 * {@link DeferredToolRegistry.Session} → <b>下一轮</b>这些工具进入 roundTools 即可调用
 * （第 N 轮发现 → 第 N+1 轮可调用）。
 *
 * <p>工具结果返回发现工具的名称 + 描述 + 参数 Schema，LLM 据此正确构造调用。
 * 每请求绑定一个 Session（实例化时传入），跨轮累积、请求间隔离。
 */
public final class ToolSearchTool {

    public static final String TOOL_NAME = "tool_search";

    private static final String TOOL_DESCRIPTION = """
            按关键词搜索并发现当前会话可用的更多工具（延迟工具发现）。

            你的初始工具清单只包含一小部分常驻工具；数据查询（SQL）、图表生成等专业能力
            默认不在清单里，需要先用本工具搜索发现。

            使用方法：
            1. 用与目标能力相关的通用关键词调用本工具，例如需要查数据库时传
               "SQL 查询 数据库 表 统计"，需要画图时传 "图表 生成 可视化"。
            2. 返回的工具列表（含名称、描述、参数说明）从下一轮起即可直接调用。
            3. 一次搜索没找到想要的工具时，换一组关键词再试（如英文工具名、同义词）。
            4. 发现的工具在本次任务内持续可用，无需重复发现。
            """;

    private ToolSearchTool() {
    }

    /** 工具入参：搜索关键词（空格分隔多个词效果更好） */
    public record SearchInput(
            @ToolParam(description = "搜索关键词，描述你需要的能力；可传多个词（空格分隔），如 \"SQL 查询 数据库 统计\"") String query) {
    }

    /** 搜索函数：query → 发现登记 + 返回工具定义清单文本 */
    public static class SearchFunction implements Function<SearchInput, String> {

        private final DeferredToolRegistry.Session session;

        public SearchFunction(DeferredToolRegistry.Session session) {
            this.session = session;
        }

        @Override
        public String apply(SearchInput input) {
            String query = input == null ? null : input.query();
            if (query == null || query.isBlank()) {
                return "参数错误：query 不能为空。请传入描述目标能力的关键词，如 \"SQL 查询 数据库\"。";
            }
            List<KeywordToolSearcher.SearchHit> hits = session.search(query);
            if (hits.isEmpty()) {
                return "没有匹配 \"" + query.trim() + "\" 的工具。请换一组关键词重试"
                        + "（例如描述工具类别的通用词：SQL / 数据库 / 查询 / 统计 / 图表 / 联网）。";
            }
            StringBuilder sb = new StringBuilder("已发现 ").append(hits.size())
                    .append(" 个工具（从下一轮起可直接调用，本任务内持续可用）：\n");
            for (KeywordToolSearcher.SearchHit hit : hits) {
                ToolCallback cb = session.callbackOf(hit.name());
                if (cb == null) {
                    continue;
                }
                var def = cb.getToolDefinition();
                sb.append("\n### ").append(def.name()).append('\n')
                        .append("描述: ").append(def.description()).append('\n')
                        .append("参数: ").append(def.inputSchema()).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 构建单例工具（每请求一个，绑定该请求的发现会话）。
     * 延迟目录为空时不构建（无东西可发现，注册即冗余）。
     */
    public static ToolCallback buildOrNull(DeferredToolRegistry registry, DeferredToolRegistry.Session session) {
        if (registry == null || session == null || registry.size() == 0) {
            return null;
        }
        return FunctionToolCallback.builder(TOOL_NAME, new SearchFunction(session))
                .description(TOOL_DESCRIPTION)
                .inputType(SearchInput.class)
                .build();
    }
}
