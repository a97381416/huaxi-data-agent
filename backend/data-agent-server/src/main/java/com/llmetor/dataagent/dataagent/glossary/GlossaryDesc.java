package com.llmetor.dataagent.dataagent.glossary;

import java.util.List;

/**
 * 业务术语条目（term + 标准口径 + 可复用 SQL 片段 + 同义词）。
 */
public record GlossaryDesc(
        String term,
        String description,
        String sqlFragment,
        List<String> synonyms) {

    public GlossaryDesc {
        synonyms = synonyms == null ? List.of() : synonyms;
    }
}
