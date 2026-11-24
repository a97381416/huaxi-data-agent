package com.llmetor.dataagent.dataagent.monitor.detect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 规则共享小工具：证据 JSON 组装 / 参数归一化哈希 / 摘录提取。
 * 全部静态、无状态；异常吞掉给兜底值（规则判定不因证据美化挂掉）。
 */
final class RuleSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();
    /** Java 堆栈行特征：at com.foo.Bar(Method.java:12) */
    static final Pattern STACK_AT = Pattern.compile("at [\\w$.]+\\(");

    private RuleSupport() {
    }

    /** 结构化证据 → JSON 字符串（落 agentx_monitor_risk.evidence jsonb；失败给 {}） */
    static String evidence(Map<String, Object> data) {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 单行截断（同 Collector.abbreviate 口径：换行折叠） */
    static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace("\r", " ").replace("\n", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }

    /** 关键词所在行的摘录（失败关键词上下文比开头更能说明问题；找不到行回退开头截断） */
    static String excerpt(String content, String keyword, int max) {
        if (content == null || content.isBlank()) {
            return "";
        }
        try {
            String lowerKw = keyword.toLowerCase();
            for (String line : content.split("\n")) {
                if (line.toLowerCase().contains(lowerKw)) {
                    return abbreviate(line, max);
                }
            }
        } catch (Exception ignored) {
            // fallthrough
        }
        return abbreviate(content, max);
    }

    /**
     * 工具调用签名（REACT-003）：逐个调用取 name + 参数归一化哈希，整体再哈希 → 8 位 hex。
     * 参数归一化 = JSON 解析后对象键递归排序（改键序不算变参）；解析失败退原文 trim。
     */
    static String callSignature(java.util.List<DetectContext.ToolCallView> calls) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < calls.size(); i++) {
            DetectContext.ToolCallView c = calls.get(i);
            if (i > 0) {
                sb.append('|');
            }
            sb.append(c.name() == null ? "" : c.name()).append('=').append(argsHash(c.arguments()));
        }
        return sha256(sb.toString());
    }

    /** 参数归一化哈希（8 位 hex，风险卡展示） */
    static String argsHash(String arguments) {
        return sha256(normalizeArgs(arguments));
    }

    /** 参数归一化：合法 JSON → 键排序规范化串（改写 SQL 等参数变化必然改变哈希——「改参重试不算」） */
    static String normalizeArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            return canonical(node);
        } catch (Exception e) {
            return raw.trim();
        }
    }

    /** JSON 规范化：对象键递归排序，值保持类型原样 */
    private static String canonical(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            obj.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(e.getKey())).append(':').append(canonical(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (node instanceof ArrayNode arr) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(canonical(arr.get(i)));
            }
            return sb.append(']').toString();
        }
        if (node.isTextual()) {
            return quote(node.textValue());
        }
        return node.toString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 4);
        } catch (Exception e) {
            return String.valueOf(Math.abs(text.hashCode()));
        }
    }

    // ==================== V2 共用：结果指纹 / SQL 词法 / 空结果特征 ====================

    /** 结果指纹单条观察规范化截断长度（REACT-003/005 口径） */
    static final int RESULT_FP_TRUNC = 500;
    /** 空结果文本特征（ Observation 规范化后含其一即视为空结果；REACT-005/TOOL-005 共用） */
    static final List<String> EMPTY_RESULT_MARKERS = List.of(
            "\"rows\":[]", "\"rows\": []", "\"rows\":null", "\"rows\": null",
            "\"count\":0", "\"count\": 0", "\"total\":0", "\"total\": 0");

    /**
     * 结果指纹（REACT-003/005）：逐条观察规范化（去全部空白、截 500）后拼接再哈希。
     * 无观察（含读取失败）= 空串指纹——「含全空」口径（05 文档 §4.5）。
     */
    static String resultFingerprint(List<DetectContext.ToolObservation> obs) {
        StringBuilder sb = new StringBuilder();
        if (obs != null) {
            for (DetectContext.ToolObservation o : obs) {
                String c = o.content() == null ? "" : o.content().replaceAll("\\s+", "");
                sb.append(c.length() > RESULT_FP_TRUNC ? c.substring(0, RESULT_FP_TRUNC) : c).append('|');
            }
        }
        return sha256(sb.toString());
    }

    /** 观察是否为空结果特征（rows:[] / count:0 / 空文本；REACT-005 条件2 与 TOOL-005 条件3 共用口径） */
    static boolean isEmptyResult(List<DetectContext.ToolObservation> obs) {
        if (obs == null || obs.isEmpty()) {
            return true; // 无观察 = 空文本特征
        }
        for (DetectContext.ToolObservation o : obs) {
            String c = o.content() == null ? "" : o.content().replaceAll("\\s+", "").toLowerCase();
            if (c.isEmpty()) {
                continue; // 该条观察为空文本
            }
            boolean empty = false;
            for (String m : EMPTY_RESULT_MARKERS) {
                if (c.contains(m)) {
                    empty = true;
                    break;
                }
            }
            if (!empty) {
                return false; // 任一条观察有实质内容 → 非空结果
            }
        }
        return true;
    }

    /**
     * SQL 词法预处理（TOOL-004 V2-D7 / TOOL-006 启发共用）：剥离行注释、块注释与字符串字面量
     * （字面量整体替换为 '' 占位——字面量里的 DELETE 等词不得参与判型）。
     */
    static String stripSqlLiteralsAndComments(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0, n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            // 行注释 -- 到行尾
            if (ch == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            // 块注释 /* ... */
            if (ch == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                out.append(' ');
                continue;
            }
            // 字符串字面量（'' 转义）
            if (ch == '\'') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2; // '' 转义的引号
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append("''"); // 占位
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    /**
     * 只读判型（TOOL-004）：词法剥离后，WITH 开头跳过全部 CTE 定义（括号配平），
     * 取主语句首个关键字 ∉ 只读白名单 {SELECT, WITH, EXPLAIN, ANALYZE, SHOW, TABLE} → 非法。
     * @return 非法时返回首个关键字（如 DROP），合法返回 null（空串/无关键字/括号不配平均合法——漏报不误报）
     */
    static String illegalWriteKeyword(String sql) {
        String s = stripSqlLiteralsAndComments(sql).trim();
        if (s.isEmpty()) {
            return null;
        }
        if (startsWithKeyword(s, "WITH")) {
            int i = 0, n = s.length();
            while (true) {
                int open = s.indexOf('(', i);
                if (open < 0) {
                    return null; // 只有 CTE 名没有定义体，保守不判
                }
                int depth = 0, j = open;
                for (; j < n; j++) {
                    char ch = s.charAt(j);
                    if (ch == '(') {
                        depth++;
                    } else if (ch == ')') {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                }
                if (depth != 0 || j >= n) {
                    return null; // 括号不配平，保守不判
                }
                int k = j + 1;
                while (k < n && Character.isWhitespace(s.charAt(k))) {
                    k++;
                }
                if (k < n && s.charAt(k) == ',') {
                    i = k + 1; // 还有下一个 CTE，继续跳
                    continue;
                }
                s = s.substring(k).trim(); // 主语句开始
                break;
            }
        }
        String first = firstKeyword(s);
        if (first == null) {
            return null;
        }
        return READONLY_KEYWORDS.contains(first) ? null : first;
    }

    private static final List<String> READONLY_KEYWORDS =
            List.of("SELECT", "WITH", "EXPLAIN", "ANALYZE", "SHOW", "TABLE");

    /**
     * SQL 残缺启发（TOOL-006）：去注释/字面量/首尾空白后——
     * ① 以 SELECT / FROM / WHERE / AND / , / ( 结尾；② SELECT 主语句缺 FROM 且列名清单以裸标识符收尾
     * （「SELECT 1」「SELECT count(*)」等常量/函数形态不算）。命中返回 true。
     */
    static boolean isTruncatedSql(String sql) {
        String s = stripSqlLiteralsAndComments(sql).trim();
        if (s.isEmpty()) {
            return false;
        }
        // ① 尾部特征：尾字符 , 或 (；尾词 ∈ {SELECT, FROM, WHERE, AND}
        char last = s.charAt(s.length() - 1);
        if (last == ',' || last == '(') {
            return true;
        }
        if (Character.isLetter(last)) {
            int i = s.length() - 1;
            while (i >= 0 && Character.isLetter(s.charAt(i))) {
                i--;
            }
            String tail = s.substring(i + 1).toUpperCase();
            if (List.of("SELECT", "FROM", "WHERE", "AND").contains(tail)) {
                return true;
            }
            // ② 缺 FROM 的 SELECT：主语句为 SELECT 且无 FROM，列清单以裸标识符收尾（排除 SELECT 1 / count(*) 形态）
            if (startsWithKeyword(s, "SELECT")) {
                String rest = s.substring(6).trim();
                boolean hasFrom = false;
                java.util.regex.Matcher m = WORD.matcher(rest);
                while (m.find()) {
                    if ("FROM".equalsIgnoreCase(m.group())) {
                        hasFrom = true;
                        break;
                    }
                }
                if (!hasFrom) {
                    return true; // 已是字母收尾（SELECT name / SELECT name, age）
                }
            }
        }
        return false;
    }

    private static final Pattern WORD = Pattern.compile("[A-Za-z_]+");

    /** 首个词是否为指定关键字（大小写不敏感、词边界） */
    private static boolean startsWithKeyword(String s, String keyword) {
        if (s.length() < keyword.length()) {
            return false;
        }
        if (!s.regionMatches(true, 0, keyword, 0, keyword.length())) {
            return false;
        }
        return s.length() == keyword.length() || !Character.isLetterOrDigit(s.charAt(keyword.length()));
    }

    /** 语句首个关键字（跳过开括号；无字母关键字返回 null） */
    private static String firstKeyword(String s) {
        int i = 0, n = s.length();
        while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == '(' || s.charAt(i) == '/')) {
            i++;
        }
        int start = i;
        while (i < n && Character.isLetter(s.charAt(i))) {
            i++;
        }
        return i == start ? null : s.substring(start, i).toUpperCase();
    }
}
