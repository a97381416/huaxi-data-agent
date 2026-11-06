package com.llmetor.miniagentx.tools.search;

/**
 * tool_search 配置（docs/02 §7 三件套之一；M7 只做 KEYWORD 模式）。
 *
 * <ul>
 *   <li><b>KEYWORD</b>：Jieba 分词 + 打分（本里程碑交付）</li>
 *   <li>LLM / HYBRID：精简 catalog 一次调用选名 / 关键词无结果才 LLM（M9 后补）</li>
 * </ul>
 *
 * @param mode       搜索模式，默认 KEYWORD
 * @param maxResults 单次发现返回的工具数上限，默认 5
 */
public record ToolSearchConfig(Mode mode, int maxResults) {

    public enum Mode {
        /** Jieba 分词 + 打分（M7） */
        KEYWORD,
        /** LLM 精简 catalog 选名（预留） */
        LLM,
        /** KEYWORD 无结果才 LLM（预留） */
        HYBRID
    }

    public ToolSearchConfig {
        if (maxResults <= 0) {
            maxResults = 5;
        }
    }

    public static ToolSearchConfig keywordDefault() {
        return new ToolSearchConfig(Mode.KEYWORD, 5);
    }
}
