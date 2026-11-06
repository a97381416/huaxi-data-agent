package com.llmetor.miniagentx.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.miniagentx.tools.SkillsTool.SkillsInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SkillsTool 单测：description 渲染 frontmatter 清单 / 按名加载返回工作目录+正文 /
 * 未命中引导 / 空目录拒绝构建 / 子层 SKILL.md 发现。
 */
class SkillsToolTest {

    private static final String ANALYSIS_MD = """
            ---
            name: data-analysis
            description: 数据分析任务——自然语言查数据、出 markdown 报告。
            ---

            # 数据分析任务流程

            第一步：listTables；第二步：validateSql；第三步：executeSql。
            """;

    private static final String BRAINSTORM_MD = """
            ---
            name: domain-name-brainstormer
            description: Generates creative domain name ideas.
            ---

            # Domain Name Brainstormer

            Generate names and check availability.
            """;

    @TempDir
    Path tempDir;

    private Path createSkillDir(String name, String content) throws Exception {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), content);
        return dir;
    }

    @Test
    void descriptionContainsFrontmatterXmlButNotBody() throws Exception {
        ToolCallback tool = SkillsTool.builder()
                .addSkillsDirectory(createSkillDir("data-analysis", ANALYSIS_MD).toString())
                .build();

        ToolDefinition def = tool.getToolDefinition();
        assertThat(def.name()).isEqualTo("Skill");

        String description = def.description();
        // frontmatter 元数据渲染成 XML 常驻
        assertThat(description).contains("<可用技能列表>")
                .contains("<skill>")
                .contains("<name>data-analysis</name>")
                .contains("<description>数据分析任务——自然语言查数据、出 markdown 报告。</description>");
        // 正文不进上下文（渐进式披露）
        assertThat(description).doesNotContain("listTables");
    }

    @Test
    void loadSkillReturnsWorkdirAndContent() throws Exception {
        Path dir = createSkillDir("data-analysis", ANALYSIS_MD);
        ToolCallback tool = SkillsTool.builder().addSkillsDirectory(dir.toString()).build();

        // 注：ToolCallback.call 返回值经 JSON 编码（框架契约，回灌 LLM 前的统一形态），断言用 contains
        String result = (String) tool.call("{\"name\":\"data-analysis\"}", null);
        assertThat(result).contains("技能工作目录: ")
                .contains("data-analysis")
                .contains("# 数据分析任务流程")
                .contains("第三步：executeSql");
    }

    @Test
    void unknownNameReturnsAvailableList() throws Exception {
        ToolCallback tool = SkillsTool.builder()
                .addSkillsDirectory(createSkillDir("data-analysis", ANALYSIS_MD).toString())
                .addSkillsDirectory(createSkillDir("domain-name-brainstormer", BRAINSTORM_MD).toString())
                .build();

        String result = (String) tool.call("{\"name\":\"pptx\"}", null);
        assertThat(result).contains("未找到技能: pptx")
                .contains("data-analysis")
                .contains("domain-name-brainstormer");
    }

    @Test
    void emptyBuilderRejected() {
        assertThatThrownBy(() -> SkillsTool.builder().build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nestedSkillMdDiscovered() throws Exception {
        // SKILL.md 在目录两层内也能发现（对齐 zip 上传 3 层内定位语义）
        Path nested = tempDir.resolve("outer").resolve("my-skill");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("SKILL.md"), ANALYSIS_MD);

        ToolCallback tool = SkillsTool.builder().addSkillsDirectory(tempDir.toString()).build();
        String result = (String) tool.call("{\"name\":\"data-analysis\"}", null);
        assertThat(result).contains("my-skill");
    }

    @Test
    void nonExistentDirectoryIgnored() throws Exception {
        Path dir = createSkillDir("data-analysis", ANALYSIS_MD);
        ToolCallback tool = SkillsTool.builder()
                .addSkillsDirectories(List.of(tempDir.resolve("ghost").toString(), dir.toString()))
                .build();
        String result = (String) tool.call("{\"name\":\"data-analysis\"}", null);
        assertThat(result).contains("技能工作目录");
    }

    @Test
    void inputSchemaHasNameParam() throws Exception {
        ToolCallback tool = SkillsTool.builder()
                .addSkillsDirectory(createSkillDir("data-analysis", ANALYSIS_MD).toString())
                .build();
        JsonNode schema = new ObjectMapper().readTree(tool.getToolDefinition().inputSchema());
        assertThat(schema.at("/properties/name/type").asText()).isEqualTo("string");
        assertThat(schema.at("/required/0").asText()).isEqualTo("name");
    }
}
