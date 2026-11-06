package com.llmetor.miniagentx.tools;

import com.llmetor.miniagentx.utils.FrontmatterUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 技能加载工具（工具名 {@code Skill}，02 文档 §7）——渐进式披露的载体。
 *
 * <p><b>单一工具 + name 参数</b>（不是每技能一个 load 工具）：build 时把所有技能的
 * frontmatter 渲染成 XML 清单拼进工具 description（元数据常驻上下文，正文不进）；
 * LLM 按 description 匹配意图 → 调用本工具传入 name → 返回"技能工作目录 + SKILL.md 正文"
 * （按需加载）→ 再按正文指引使用其他工具完成任务。scripts/references 资源再按需读取。
 *
 * <p>扫描给定技能目录（每目录一个技能，含 SKILL.md）；frontmatter 解析只支持单行 key:value
 * （{@link FrontmatterUtils}）。
 */
public final class SkillsTool {

    private static final String TOOL_DESCRIPTION_TEMPLATE = """
            在当前会话中加载一个技能（Skill）。本工具的唯一作用是：传入技能名称，获取该技能的完整提示词和工作目录。

            <什么是技能>
            技能是一段专业的提示词，包含特定领域的知识、工作流程和操作指令。
            每个技能通常还附带参考文件、模板、脚本等资源，存放在技能工作目录中。
            </什么是技能>

            <技能的完整使用流程>
            第一步 — 判断是否需要技能：
              当用户要求完成某项任务时，先检查下方 <可用技能列表> 中是否有匹配的技能。
              如果有匹配的技能，进入第二步；如果没有，直接用你自身能力回答即可。

            第二步 — 通过本工具加载技能：
              调用本工具，传入技能的 name 字段值（仅传名称，不含任何参数）。
              调用后你会收到：技能工作目录路径 + 技能的完整提示词内容。

            第三步 — 阅读并理解技能提示词：
              仔细阅读技能返回的完整提示词，理解该技能的工作流程和要求。

            第四步 — 按技能提示词执行任务：
              严格按照技能提示词中的指令和流程来完成任务。
              你需要像"演员进入角色"一样，完全遵照技能提示词的指引行动。
              如果技能工作目录中有参考文件、模板、脚本，根据需要读取和使用它们。
              使用其他工具来完成技能要求的具体操作。
            </技能的完整使用流程>

            <关键概念：技能不是工具>
            技能（Skill）和工具（Tool）是两个不同的概念：
            - 工具：你可以直接调用的能力，如搜索、文件读取等
            - 技能：是一段提示词/指令，通过本工具加载后，你按照其中的指引去行动
            技能本身不是工具，不能被当作工具调用。技能的正确使用方式是：
            用本工具加载 → 阅读提示词 → 按提示词中的指令，使用真正的工具来完成任务
            </关键概念：技能不是工具>

            <严格禁止>
            - 禁止将技能名称当作独立的工具来调用
            - 禁止在未通过本工具加载的情况下，假装已经知道技能的内容
            - 禁止编造或猜测 <可用技能列表> 中不存在的技能名称
            - 禁止重复加载同一个技能（同一技能在一次对话中只需加载一次）
            - 禁止加载技能后忽略其提示词内容，自行发挥
            </严格禁止>

            <可用技能列表>
            %s
            </可用技能列表>
            """;

    private SkillsTool() {
    }

    /** 工具入参：仅技能名（02 §7：单一工具 + name 参数） */
    public record SkillsInput(
            @ToolParam(description = "要加载的技能名称（仅传名称，不含任何参数），例如 \"data-analysis\"") String name) {
    }

    /** 加载函数：命中返回"技能工作目录 + SKILL.md 正文"，未命中返回可用技能清单引导重试 */
    public static class SkillsFunction implements Function<SkillsInput, String> {

        private final Map<String, Skill> skillsMap;

        public SkillsFunction(Map<String, Skill> skillsMap) {
            this.skillsMap = skillsMap;
        }

        @Override
        public String apply(SkillsInput input) {
            String name = input == null ? null : input.name();
            Skill skill = name == null ? null : this.skillsMap.get(name.trim());

            if (skill != null) {
                return "技能工作目录: %s\n\n%s".formatted(skill.basePath(), skill.content());
            }
            String availableNames = String.join(", ", this.skillsMap.keySet());
            return "未找到技能: " + (name == null ? "" : name) + "。当前可用技能: " + availableNames;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<Skill> skills = new ArrayList<>();

        private String toolDescriptionTemplate = TOOL_DESCRIPTION_TEMPLATE;

        private Builder() {
        }

        /** 覆盖默认工具描述模板（模板须含一个 %s 占位技能清单） */
        public Builder toolDescriptionTemplate(String template) {
            this.toolDescriptionTemplate = template;
            return this;
        }

        /** 批量追加技能目录（每目录一个技能，含 SKILL.md 即视为有效技能） */
        public Builder addSkillsDirectories(List<String> skillsRootDirectories) {
            for (String dir : skillsRootDirectories) {
                this.skills.addAll(loadDirectory(dir));
            }
            return this;
        }

        /** 追加单个技能目录 */
        public Builder addSkillsDirectory(String skillsRootDirectory) {
            return this.addSkillsDirectories(List.of(skillsRootDirectory));
        }

        /**
         * 构建单例工具：全部技能 frontmatter 渲染成 XML 拼进 description（常驻），
         * 正文只在调用时返回（渐进式披露）。同名技能后注册者不覆盖先注册者。
         */
        public ToolCallback build() {
            if (this.skills.isEmpty()) {
                throw new IllegalStateException("至少需要配置一个技能目录");
            }
            // LinkedHashMap：同名保留先注册者，且渲染顺序稳定
            Map<String, Skill> skillsMap = new LinkedHashMap<>();
            for (Skill skill : this.skills) {
                skillsMap.putIfAbsent(skill.name(), skill);
            }
            String skillsXml = skillsMap.values().stream()
                    .map(Skill::toXml)
                    .collect(Collectors.joining("\n"));

            return FunctionToolCallback.builder("Skill", new SkillsFunction(skillsMap))
                    .description(this.toolDescriptionTemplate.formatted(skillsXml))
                    .inputType(SkillsInput.class)
                    .build();
        }
    }

    // ========== 技能数据模型 ==========

    /** 一个技能：工作目录（绝对路径）+ frontmatter 元数据 + SKILL.md 正文 */
    public record Skill(String basePath, Map<String, Object> frontMatter, String content) {

        /** 技能名（frontmatter 的 name，缺失回退空串——加载方按目录名兜底后传入） */
        public String name() {
            Object name = this.frontMatter().get("name");
            return name != null ? name.toString() : "";
        }

        /** frontmatter 渲染成 XML 清单项（常驻工具 description 的最小元数据） */
        public String toXml() {
            String frontMatterXml = this.frontMatter().entrySet().stream()
                    .map(e -> "  <%s>%s</%s>".formatted(e.getKey(), e.getValue(), e.getKey()))
                    .collect(Collectors.joining("\n"));
            return "<skill>\n%s\n</skill>".formatted(frontMatterXml);
        }
    }

    // ========== 技能目录扫描 ==========

    /** 扫描目录下（含子层）的 SKILL.md（大小写不敏感），每个文件即一个技能 */
    private static List<Skill> loadDirectory(String skillsRootDirectory) {
        List<Skill> skills = new ArrayList<>();
        Path root = Path.of(skillsRootDirectory);

        if (!Files.isDirectory(root)) {
            return skills;
        }

        try {
            Files.walk(root, FileVisitOption.FOLLOW_LINKS)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("SKILL.md"))
                    .forEach(skillFile -> {
                        try {
                            String markdown = Files.readString(skillFile, StandardCharsets.UTF_8);
                            String content = markdown;
                            // 正文 = 去掉 frontmatter 首段（--- 包裹）后的剩余部分
                            if (markdown.startsWith("---")) {
                                int endIndex = markdown.indexOf("---", 3);
                                if (endIndex != -1) {
                                    content = markdown.substring(endIndex + 3).trim();
                                }
                            }
                            skills.add(new Skill(skillFile.getParent().toAbsolutePath().toString(),
                                    FrontmatterUtils.parseAll(markdown), content));
                        } catch (IOException e) {
                            throw new IllegalStateException("解析技能文件失败: " + skillFile, e);
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("扫描技能目录失败: " + skillsRootDirectory, e);
        }

        return skills;
    }
}
