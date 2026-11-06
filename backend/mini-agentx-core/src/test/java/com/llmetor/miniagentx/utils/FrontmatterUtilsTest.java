package com.llmetor.miniagentx.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FrontmatterUtils 单测：单 key 提取 / 全量解析 / 引号剥离 / 边界形态。
 */
class FrontmatterUtilsTest {

    private static final String SKILL_MD = """
            ---
            name: data-analysis
            description: 数据分析任务——自然语言查数据、出 markdown 报告。
            userInvocable: true
            ---

            # 数据分析任务流程

            正文内容，含 --- 分隔线不应被误认为 frontmatter 结束。
            """;

    @TempDir
    Path tempDir;

    @Test
    void extractSingleKey() {
        assertThat(FrontmatterUtils.extract(SKILL_MD, "name")).isEqualTo("data-analysis");
        assertThat(FrontmatterUtils.extract(SKILL_MD, "description"))
                .isEqualTo("数据分析任务——自然语言查数据、出 markdown 报告。");
    }

    @Test
    void extractMissingKeyReturnsNull() {
        assertThat(FrontmatterUtils.extract(SKILL_MD, "version")).isNull();
        assertThat(FrontmatterUtils.extract(null, "name")).isNull();
        assertThat(FrontmatterUtils.extract("没有 frontmatter 的文档", "name")).isNull();
    }

    @Test
    void extractStripsQuotes() {
        String md = """
                ---
                name: 'quoted-name'
                description: "double quoted"
                ---
                正文
                """;
        assertThat(FrontmatterUtils.extract(md, "name")).isEqualTo("quoted-name");
        assertThat(FrontmatterUtils.extract(md, "description")).isEqualTo("double quoted");
    }

    @Test
    void parseAllKeepsOrderAndValues() {
        Map<String, Object> all = FrontmatterUtils.parseAll(SKILL_MD);
        assertThat(all).containsOnlyKeys("name", "description", "userInvocable");
        assertThat(all.get("name")).isEqualTo("data-analysis");
        assertThat(all.get("userInvocable")).isEqualTo("true");
        // 保持声明顺序
        assertThat(all.keySet()).containsExactly("name", "description", "userInvocable");
    }

    @Test
    void parseAllNoFrontmatterReturnsEmpty() {
        assertThat(FrontmatterUtils.parseAll("plain text")).isEmpty();
        assertThat(FrontmatterUtils.parseAll(null)).isEmpty();
        // 只有开头 --- 没有闭合：视为无 frontmatter
        assertThat(FrontmatterUtils.parseAll("---\nname: x")).isEmpty();
    }

    @Test
    void extractFromFile() throws Exception {
        Path file = tempDir.resolve("SKILL.md");
        Files.writeString(file, SKILL_MD);
        assertThat(FrontmatterUtils.extractFromFile(file, "name")).isEqualTo("data-analysis");
        assertThat(FrontmatterUtils.extractFromFile(tempDir.resolve("nope.md"), "name")).isNull();
    }
}
