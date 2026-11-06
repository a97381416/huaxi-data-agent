package com.llmetor.miniagentx.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 坏 JSON 修复工具（结构化输出必带）。
 *
 * <p>7 步修复（对齐原框架）：剥 markdown 围栏 → 剪首尾非 JSON 杂质 → 状态机修引号 →
 * 去尾逗号 → 补键名引号 → 修非法转义 → 校验，全部失败兜底包成 {"content": "..."}。
 */
public final class JsonRepairUtil {

    private static final Pattern FENCED = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);

    private JsonRepairUtil() {
    }

    /**
     * 修复 LLM 输出的 JSON 文本。绝不抛异常：任何失败都会退化为 {"content":"原文"}。
     */
    public static String fixJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{\"content\":\"\"}";
        }
        String s = stripFence(raw.trim());
        s = trimToWrapper(s);
        s = fixQuotes(s);
        s = removeTrailingCommas(s);
        s = quoteBareKeys(s);
        s = fixInvalidEscapes(s);
        if (isValidJson(s)) {
            return s;
        }
        // 兜底：包成 content 字段，保证调用方拿到合法 JSON
        String escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
        return "{\"content\":\"" + escaped + "\"}";
    }

    /** ① 剥 markdown 围栏（```json ... ```） */
    static String stripFence(String s) {
        Matcher m = FENCED.matcher(s);
        if (m.find()) {
            return m.group(1).trim();
        }
        return s;
    }

    /** ② 剪首尾杂质：从第一个 [ 或 { 到最后一个 ] 或 } */
    static String trimToWrapper(String s) {
        int first = -1, last = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '[') {
                first = i;
                break;
            }
        }
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '}' || c == ']') {
                last = i;
                break;
            }
        }
        if (first >= 0 && last > first) {
            return s.substring(first, last + 1);
        }
        return s;
    }

    /**
     * ③ 状态机修引号：把内容中的智能引号（“ ” ‘ ’）换成普通引号。
     * （保持轻量：只做安全替换，不动字符串内部语义）
     */
    static String fixQuotes(String s) {
        return s.replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'');
    }

    /** ④ 去尾逗号：] 或 } 前的逗号 */
    static String removeTrailingCommas(String s) {
        return s.replaceAll(",\\s*([}\\]])", "$1");
    }

    /** ⑤ 补键名引号：{ key: / , key: 形式的裸键 */
    static String quoteBareKeys(String s) {
        return s.replaceAll("([\\{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*:", "$1\"$2\":");
    }

    /** ⑥ 修非法转义：反斜杠后跟非法转义字符时去掉反斜杠 */
    static String fixInvalidEscapes(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (isLegalEscape(next)) {
                    out.append(c).append(next);
                    i++;
                    continue;
                }
                // 非法转义：丢弃反斜杠
                continue;
            }
            if (c == '"') {
                inString = !inString;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isLegalEscape(char c) {
        return c == '"' || c == '\\' || c == '/' || c == 'b' || c == 'f'
                || c == 'n' || c == 'r' || c == 't' || c == 'u';
    }

    /** ⑦ 校验：括号配平 + 首尾包裹字符匹配（不引 JSON 库，保持零依赖可用性） */
    public static boolean isValidJson(String s) {
        if (s.length() < 2) {
            return false;
        }
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if (!((first == '{' && last == '}') || (first == '[' && last == ']'))) {
            return false;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> depth++;
                case '}', ']' -> {
                    depth--;
                    if (depth < 0) {
                        return false;
                    }
                }
                default -> {
                }
            }
        }
        return depth == 0 && !inString;
    }
}
