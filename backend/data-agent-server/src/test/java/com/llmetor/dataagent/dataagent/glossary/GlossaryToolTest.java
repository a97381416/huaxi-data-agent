package com.llmetor.dataagent.dataagent.glossary;

import com.llmetor.dataagent.dataagent.tools.LookupGlossaryTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GlossaryCatalog + LookupGlossaryTool 单元测试（M-H3 华西版）。
 * 真实加载 classpath:schema/glossary.yml（华西 42 条定稿版），
 * 校验术语/同义词双索引精确匹配与未命中提示。
 */
class GlossaryToolTest {

    private final GlossaryCatalog catalog = new GlossaryCatalog();

    private String lookup(String term) throws Exception {
        ToolCallback cb = LookupGlossaryTool.builder().terms(catalog.listAll()).build();
        String args = "{\"term\":\"" + term + "\"}";
        return cb.call(args, null);
    }

    @Test
    void yamlLoadedWithHuaxiTerms() {
        assertFalse(catalog.listAll().isEmpty(), "glossary.yml 应加载出术语");
        assertTrue(catalog.listAll().stream().anyMatch(g -> "问数总纲".equals(g.term())),
                "元规则术语「问数总纲」必须存在");
        assertTrue(catalog.listAll().stream().anyMatch(g -> "备案率".equals(g.term())),
                "官方指标术语「备案率」必须存在");
        assertTrue(catalog.listAll().size() >= 40, "华西 glossary 定稿 42 条");
    }

    @Test
    void exactTermHit() throws Exception {
        String out = lookup("备案率");
        assertTrue(out.contains("【术语】备案率"));
        assertTrue(out.contains("【SQL 片段"));
        assertTrue(out.contains("uploaded_count"), "口径必须是官方公式（stats 快照列）");
    }

    @Test
    void synonymHit() throws Exception {
        String out = lookup("竣工");
        assertTrue(out.contains("【术语】项目状态"), "同义词「竣工」应命中「项目状态」");
        String out2 = lookup("完备率");
        assertTrue(out2.contains("【术语】备案率"), "同义词「完备率」应命中「备案率」");
    }

    @Test
    void missReturnsFullTermList() throws Exception {
        String out = lookup("不存在的术语xyz");
        assertTrue(out.contains("未找到术语"));
        assertTrue(out.contains("问数总纲"), "未命中时返回全部术语清单供重试");
        assertTrue(out.contains("穿透模型"));
    }

    @Test
    void toolDescriptionContainsAllTerms() {
        ToolCallback cb = LookupGlossaryTool.builder().terms(catalog.listAll()).build();
        String desc = cb.getToolDefinition().description();
        assertTrue(desc.contains("<可用术语列表>"));
        assertTrue(desc.contains("问数总纲"));
        assertTrue(desc.contains("备案率"));
        assertTrue(desc.contains("万元"), "工具描述需含单位口径");
    }

    @Test
    void blankTermHintedBack() throws Exception {
        String out = lookup("");
        assertTrue(out.contains("入参为空"));
    }
}
