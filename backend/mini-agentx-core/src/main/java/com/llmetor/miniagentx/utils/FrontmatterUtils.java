package com.llmetor.miniagentx.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Markdown frontmatter（YAML 首段两个 {@code ---} 之间）极简解析工具。
 *
 * <p>SkillsTool 用：只支持单行 {@code key: value}（02 文档 §10 明确不做列表/嵌套 YAML），
 * 不引入完整 YAML 解析依赖，按行扫描提取。
 */
public final class FrontmatterUtils {

    private static final String DELIMITER = "---";

    private FrontmatterUtils() {
    }

    /**
     * 直接从文件读取并提取 frontmatter 值。
     *
     * @param file Markdown 文件路径
     * @param key  frontmatter 中的 key（如 "name"、"description"）
     * @return value 值，文件不存在或 key 不存在返回 null
     */
    public static String extractFromFile(Path file, String key) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return extract(Files.readString(file), key);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从 Markdown frontmatter 提取指定 key 的 value（两个 {@code ---} 之间的首个 {@code key: value} 行）。
     *
     * @param content Markdown 全文
     * @param key     frontmatter 中的 key
     * @return value 值（去除首尾空白与成对引号），找不到返回 null
     */
    public static String extract(String content, String key) {
        if (content == null) {
            return null;
        }
        String[] lines = content.split("\\r?\\n");
        boolean inFrontmatter = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (DELIMITER.equals(trimmed)) {
                if (inFrontmatter) {
                    break;
                }
                inFrontmatter = true;
                continue;
            }
            if (inFrontmatter && trimmed.startsWith(key + ":")) {
                return removeQuotes(trimmed.substring((key + ":").length()).trim());
            }
        }
        return null;
    }

    /**
     * 解析全部 frontmatter 键值对（SkillsTool 渲染元数据清单用）。
     *
     * @param markdown Markdown 全文
     * @return 有序键值对（保持声明顺序）；无 frontmatter 返回空 Map
     */
    public static Map<String, Object> parseAll(String markdown) {
        Map<String, Object> frontMatter = new LinkedHashMap<>();
        if (markdown == null || !markdown.startsWith(DELIMITER)) {
            return frontMatter;
        }
        int endIndex = markdown.indexOf(DELIMITER, 3);
        if (endIndex == -1) {
            return frontMatter;
        }
        for (String line : markdown.substring(3, endIndex).split("\n")) {
            String trimmed = line.trim();
            int colonIndex = trimmed.indexOf(':');
            if (trimmed.isEmpty() || colonIndex <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colonIndex).trim();
            String value = removeQuotes(trimmed.substring(colonIndex + 1).trim());
            frontMatter.put(key, value);
        }
        return frontMatter;
    }

    /**
     * 剥离 frontmatter 首段（两个 {@code ---} 之间），返回其后的正文。
     * 无 frontmatter 或首段不完整时原样返回。
     */
    public static String stripFrontmatter(String markdown) {
        if (markdown == null || !markdown.startsWith(DELIMITER)) {
            return markdown;
        }
        int endIndex = markdown.indexOf(DELIMITER, 3);
        if (endIndex == -1) {
            return markdown;
        }
        return markdown.substring(endIndex + DELIMITER.length()).stripLeading();
    }

    /**
     * 拼装 frontmatter + 正文（{@link #parseAll} 的逆操作，单行 {@code key: value}）。
     * value 中的换行折叠为空格（解析器只支持单行）；body 自带 frontmatter 时先剥离再拼接。
     *
     * @param frontmatter 有序键值对（LinkedHashMap 保持声明顺序）
     * @param body        Markdown 正文
     */
    public static String assemble(Map<String, Object> frontmatter, String body) {
        StringBuilder sb = new StringBuilder(DELIMITER).append('\n');
        for (Map.Entry<String, Object> entry : frontmatter.entrySet()) {
            String value = String.valueOf(entry.getValue()).replaceAll("\\s*\\r?\\n\\s*", " ").trim();
            sb.append(entry.getKey()).append(": ").append(value).append('\n');
        }
        sb.append(DELIMITER).append("\n\n");
        String stripped = stripFrontmatter(body);
        if (stripped != null && !stripped.isBlank()) {
            sb.append(stripped.stripLeading());
            if (sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** 去除成对的单/双引号（frontmatter 值常见写法） */
    private static String removeQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
