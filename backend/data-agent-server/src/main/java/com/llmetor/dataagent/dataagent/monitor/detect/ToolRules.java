package com.llmetor.dataagent.dataagent.monitor.detect;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.MAPPER;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.abbreviate;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.evidence;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.excerpt;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.isEmptyResult;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.illegalWriteKeyword;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.isTruncatedSql;

/**
 * Tools 工具调用规则 7 条（V2 规格 §4.6~4.12）：调用失败（结构化状态优先 V2-D2）/
 * 耗时异常（按工具阈值）/ 返回格式异常（按工具期望输出）/ SQL 非法操作（词法分析 V2-D7）/
 * 统计查询空结果提示 / 参数异常（V2 新增）/ API 业务执行失败（V2 新增）。
 * 数据源 = payload 的 tool_calls 与次轮观察。
 */
final class ToolRules {

    private ToolRules() {
    }

    static List<DetectRule> all() {
        return List.of(new ToolCallFailed(), new ToolTooSlow(), new ToolResultMalformed(),
                new SqlIllegalWrite(), new SqlEmptyStatResult(), new ArgAnomaly(), new ApiBizFail());
    }

    /** arguments 里是否出现了含 SQL 特征关键词的工具（TOOL-004/005/006 共用） */
    static boolean isSqlTool(String toolName, List<String> sqlKeywords) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        String lower = toolName.toLowerCase();
        return sqlKeywords.stream().anyMatch(kw -> !kw.isBlank() && lower.contains(kw.toLowerCase()));
    }

    /** 本轮 tool_calls 里是否有 SQL 类工具 */
    static boolean roundHasSqlTool(DetectContext ctx, DetectContext.TraceRow row, List<String> sqlKeywords) {
        return ctx.toolCalls(row.round()).stream()
                .anyMatch(c -> isSqlTool(c.name(), sqlKeywords));
    }

    // ==================== TOOL-001 工具调用失败（V2-D2 结构化状态优先） ====================

    /**
     * V2 两级判定（§4.6）：
     * ① 失败轮 error_message 含工具名（trace success=0，确定性失败）；② 决策轮的次轮观察——
     * 先解析 JSON 结构化状态：status∈{error,failed,failure} / success=false / error_code 非空非 0 /
     * exception 非空 / timeout=true，任一成立 → 命中；success=true 或 status∈{ok,success,completed}
     * → 不进关键词兜底；无可识别结构化状态时<b>全文</b>扫描失败特征词（V1 的 300 字符窗口取消）。
     *
     * <p>V2 变更：docToolNames 默认改空（参数保留兼容，配置后文档类工具观察整条豁免）；
     * 观察截断长度走 obsTruncateChars（默认 8000，payload 访问器 SQL 侧生效）。
     */
    static final class ToolCallFailed implements DetectRule {

        static final List<String> DEFAULT_KEYWORDS =
                List.of("error", "exception", "timeout", "failed", "失败", "超时", "cancelled");
        /** V2 默认空（V1 曾默认 tool_search/listTables/lookupGlossary，取消文档豁免；参数保留兼容） */
        static final List<String> DEFAULT_DOC_TOOLS = List.of();
        /** 文本成功标记前缀（校准 2026-11-20：executeSql 成功观察 "✅ 执行成功…" 的返回数据含 timeout/error 词，
         *  关键词兜底误报——显式文本成功标记视同结构化成功，跳过兜底） */
        static final List<String> DEFAULT_SUCCESS_MARKERS = List.of("✅ 执行成功");
        static final Set<String> FAIL_STATUS = Set.of("error", "failed", "failure");
        static final Set<String> OK_STATUS = Set.of("ok", "success", "completed");

        @Override
        public String id() {
            return "TOOL-001";
        }

        @Override
        public String title() {
            return "工具调用失败";
        }

        @Override
        public String severity() {
            return "high";
        }

        @Override
        public String description() {
            return "失败轮错误含工具名，或工具观察 JSON 结构化状态为失败（status/success/error_code/"
                    + "exception/timeout），无结构化状态时全文命中失败特征词";
        }

        @Override
        public Map<String, Object> defaultParams() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("failKeywords", DEFAULT_KEYWORDS);
            params.put("docToolNames", DEFAULT_DOC_TOOLS);
            params.put("successMarkers", DEFAULT_SUCCESS_MARKERS);
            params.put("obsTruncateChars", 8000);
            return params;
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                DetectConfig cfg = ctx.config();
                List<String> keywords = cfg.listParam(id(), "failKeywords", DEFAULT_KEYWORDS);
                List<String> docTools = cfg.listParam(id(), "docToolNames", DEFAULT_DOC_TOOLS);
                List<String> successMarkers = cfg.listParam(id(), "successMarkers", DEFAULT_SUCCESS_MARKERS);
                Map<Integer, RiskHit> byRound = new LinkedHashMap<>();

                // ① 失败轮：error_message 含本轮工具名 → 定位该轮（trace success=0 确定性失败）
                for (DetectContext.TraceRow r : ctx.rounds()) {
                    if (r.success() || r.errorMessage() == null || r.errorMessage().isBlank()) {
                        continue;
                    }
                    String errLower = r.errorMessage().toLowerCase();
                    for (String tool : r.toolNames() == null ? List.<String>of() : r.toolNames()) {
                        if (tool != null && !tool.isBlank() && errLower.contains(tool.toLowerCase())) {
                            byRound.put(r.round(), new RiskHit(id(), severity(), r.round(), r.traceId(), null,
                                    "第 " + r.round() + " 轮工具 " + tool + " 调用失败："
                                            + abbreviate(r.errorMessage(), 120),
                                    evidence(Map.of("round", r.round(), "tool", tool,
                                            "source", "trace-error",
                                            "error", abbreviate(r.errorMessage(), 200)))));
                            break;
                        }
                    }
                }

                // ② 决策轮：次轮尾段观察——结构化状态优先，全文关键词兜底
                for (DetectContext.TraceRow r : ctx.rounds()) {
                    if (byRound.containsKey(r.round())) {
                        continue; // 该轮已按 ① 命中，不重复出卡
                    }
                    List<DetectContext.ToolObservation> obs = ctx.observationsAfter(r.round());
                    if (obs.isEmpty()) {
                        continue;
                    }
                    Set<String> tools = new LinkedHashSet<>();
                    String firstExcerpt = null;
                    Map<String, Object> firstEvidence = null;
                    for (DetectContext.ToolObservation o : obs) {
                        if (isDocTool(o.name(), docTools)) {
                            continue; // 配置的文档类工具观察豁免（V2 默认清单为空，即不豁免）
                        }
                        String content = o.content() == null ? "" : o.content();
                        String tool = o.name() == null || o.name().isBlank() ? "tool" : o.name();
                        // ②-a 结构化状态判定（JSON 对象才检查）
                        Map<String, Object> structured = structuredFail(content);
                        if (structured != null) {
                            tools.add(tool);
                            if (firstEvidence == null) {
                                firstEvidence = new LinkedHashMap<>();
                                firstEvidence.put("round", r.round());
                                firstEvidence.put("tool", tool);
                                firstEvidence.put("source", "structured");
                                firstEvidence.putAll(structured);
                                firstExcerpt = abbreviate(content, 120);
                            }
                            continue;
                        }
                        if (structuredOk(content)) {
                            continue; // 结构化成功形态（success=true / status ok / error_code=0）→ 不进关键词兜底
                        }
                        // 观察内容可能是 JSON 字符串化形态（首尾带引号），剥引号后再比成功标记前缀
                        String stripped = content.strip();
                        while (stripped.startsWith("\"")) {
                            stripped = stripped.substring(1).strip();
                        }
                        final String trimmedContent = stripped;
                        boolean textualSuccess = successMarkers.stream().anyMatch(m -> !m.isBlank()
                                && trimmedContent.startsWith(m));
                        if (textualSuccess) {
                            continue; // 文本成功标记开头（如 "✅ 执行成功"）→ 正文中 fail 词属于数据内容，不判
                        }
                        // ②-b 关键词兜底：全文扫描（V2 取消 300 字符窗口）
                        String cleaned = stripNullErrorField(content);
                        String lower = cleaned.toLowerCase();
                        for (String kw : keywords) {
                            if (kw == null || kw.isBlank()) {
                                continue;
                            }
                            if (lower.contains(kw.toLowerCase())) {
                                tools.add(tool);
                                if (firstEvidence == null) {
                                    firstEvidence = new LinkedHashMap<>();
                                    firstEvidence.put("round", r.round());
                                    firstEvidence.put("tool", tool);
                                    firstEvidence.put("source", "keyword");
                                    firstEvidence.put("keywords", List.of(kw));
                                    firstExcerpt = excerpt(cleaned, kw, 120);
                                }
                                break; // 每条观察命中一个词即算
                            }
                        }
                    }
                    if (firstEvidence != null) {
                        String detail = "structured".equals(firstEvidence.get("source"))
                                ? "第 " + r.round() + " 轮调用工具 " + String.join(",", tools)
                                        + " 后观察返回失败状态（" + firstEvidence.get("field") + "="
                                        + firstEvidence.get("value") + "）：" + firstExcerpt
                                : "第 " + r.round() + " 轮调用工具 " + String.join(",", tools)
                                        + " 后观察报错：" + firstExcerpt;
                        byRound.put(r.round(), new RiskHit(id(), severity(), r.round(), r.traceId(), null,
                                detail, evidence(firstEvidence)));
                    }
                }
                return new ArrayList<>(byRound.values());
            } catch (Exception e) {
                return List.of();
            }
        }

        /**
         * JSON 结构化失败状态（V2-D2）：可解析为 JSON 对象时检查
         * status∈{error,failed,failure} / success=false / error_code 非空非 0 / exception 非空 /
         * timeout=true。命中返回证据（field/value），否则 null。
         */
        static Map<String, Object> structuredFail(String content) {
            JsonNode node = tryParseObject(content);
            if (node == null) {
                return null;
            }
            String status = textOrNull(node.get("status"));
            if (status != null && FAIL_STATUS.contains(status.toLowerCase())) {
                return fieldEvidence("status", status);
            }
            JsonNode success = node.get("success");
            if (success != null && success.isBoolean() && !success.booleanValue()) {
                return fieldEvidence("success", "false");
            }
            JsonNode errorCode = node.get("error_code");
            if (errorCode != null && !errorCode.isNull()) {
                String text = textOrNull(errorCode);
                boolean zero = errorCode.isNumber() && errorCode.decimalValue().signum() == 0;
                boolean blank = text != null && text.isBlank();
                if (!zero && !blank && (text != null || errorCode.isNumber())) {
                    return fieldEvidence("error_code", text != null ? text : errorCode.toString());
                }
            }
            String exception = textOrNull(node.get("exception"));
            if (exception != null && !exception.isBlank()) {
                return fieldEvidence("exception", abbreviate(exception, 80));
            }
            JsonNode timeout = node.get("timeout");
            boolean timeoutTrue = (timeout != null && timeout.isBoolean() && timeout.booleanValue())
                    || ("true".equalsIgnoreCase(textOrNull(timeout)));
            if (timeoutTrue) {
                return fieldEvidence("timeout", "true");
            }
            return null;
        }

        /** JSON 结构化成功形态：success=true / status∈{ok,success,completed} / error_code=0|空（跳过关键词兜底用） */
        static boolean structuredOk(String content) {
            JsonNode node = tryParseObject(content);
            if (node == null) {
                return false;
            }
            JsonNode success = node.get("success");
            if (success != null && success.isBoolean() && success.booleanValue()) {
                return true;
            }
            String status = textOrNull(node.get("status"));
            if (status != null && OK_STATUS.contains(status.toLowerCase())) {
                return true;
            }
            // error_code 为 null / 0 / 空串是业务成功约定（键名含 "error"，须挡住关键词兜底误报）
            JsonNode errorCode = node.get("error_code");
            if (errorCode != null && (errorCode.isNull()
                    || (errorCode.isNumber() && errorCode.decimalValue().signum() == 0)
                    || (errorCode.isTextual() && errorCode.textValue().isBlank()))) {
                return true;
            }
            return false;
        }

        private static JsonNode tryParseObject(String content) {
            try {
                String t = content == null ? "" : content.trim();
                if (!t.startsWith("{")) {
                    return null;
                }
                JsonNode node = MAPPER.readTree(t);
                return node.isObject() ? node : null;
            } catch (Exception e) {
                return null;
            }
        }

        private static String textOrNull(JsonNode node) {
            if (node == null || node.isNull()) {
                return null;
            }
            return node.isTextual() ? node.textValue() : node.asText();
        }

        private static Map<String, Object> fieldEvidence(String field, String value) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", field);
            m.put("value", value);
            return m;
        }

        /** 观察是否来自配置的文档/上下文类工具（名字精确忽略大小写匹配清单） */
        static boolean isDocTool(String toolName, List<String> docTools) {
            if (toolName == null || toolName.isBlank()) {
                return false;
            }
            String lower = toolName.toLowerCase();
            return docTools.stream().anyMatch(t -> t != null && !t.isBlank() && lower.equals(t.toLowerCase()));
        }

        /** JSON 成功形态 "error": null / "" 是常见返回（schema 工具约定），替换为中性占位后再扫描 */
        static String stripNullErrorField(String content) {
            return content.replaceAll("(?i)\"error\"\\s*:\\s*(null|\"\")", "\"ok\"");
        }
    }

    // ==================== TOOL-002 工具调用耗时异常（按工具阈值） ====================

    /**
     * V2（§4.7，改名）：单工具轮查 perToolMs[toolName]（未配置用 defaultMs）；
     * 多工具轮无法归因单工具，一律用 defaultMs（宽松避免误报）。超阈值 → 命中（提示级）。
     * defaultMs 兼容读旧键 maxRoundMs。单问最多 5 条防刷屏。
     */
    static final class ToolTooSlow implements DetectRule {

        static final int MAX_HITS_PER_SESSION = 5;
        static final Map<String, Object> DEFAULT_PER_TOOL_MS = Map.of(
                "executeSql", 5000, "execute_sql", 5000, "Skill", 3000, "search_documents", 5000);

        @Override
        public String id() {
            return "TOOL-002";
        }

        @Override
        public String title() {
            return "工具调用耗时异常";
        }

        @Override
        public String severity() {
            return "low";
        }

        @Override
        public String description() {
            return "工具调用耗时超过该工具阈值（perToolMs 未配置用 defaultMs；多工具轮用 defaultMs，提示级）";
        }

        @Override
        public Map<String, Object> defaultParams() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("defaultMs", 30_000);
            params.put("perToolMs", DEFAULT_PER_TOOL_MS);
            return params;
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                long defaultMs = ctx.config().intParamCompat(id(), "defaultMs", "maxRoundMs", 30_000);
                Map<String, Object> perTool = ctx.config().mapParam(id(), "perToolMs", DEFAULT_PER_TOOL_MS);
                List<RiskHit> hits = new ArrayList<>();
                int extra = 0;
                for (DetectContext.TraceRow r : ctx.rounds()) {
                    Long ms = r.durationMs();
                    if (ms == null || ms <= 0) {
                        continue;
                    }
                    List<String> names = r.toolNames() == null ? List.of() : r.toolNames();
                    long threshold = defaultMs;
                    String tool = null;
                    if (names.size() == 1 && names.get(0) != null && !names.get(0).isBlank()) {
                        tool = names.get(0);
                        Object v = perTool.get(tool);
                        if (v instanceof Number n && n.longValue() > 0) {
                            threshold = n.longValue();
                        }
                    }
                    if (ms <= threshold) {
                        continue;
                    }
                    if (hits.size() >= MAX_HITS_PER_SESSION) {
                        extra++;
                        continue;
                    }
                    String label = tool != null && threshold != defaultMs
                            ? "工具 " + tool + "（阈值 " + (threshold / 1000) + "s）"
                            : "（阈值 " + (threshold / 1000) + "s）";
                    hits.add(new RiskHit(id(), severity(), r.round(), r.traceId(), null,
                            "第 " + r.round() + " 轮耗时 " + (ms / 1000.0) + "s，超过" + label,
                            evidence(Map.of("round", r.round(), "tool", tool == null ? "" : tool,
                                    "durationMs", ms, "thresholdMs", threshold))));
                }
                if (extra > 0 && !hits.isEmpty()) {
                    RiskHit last = hits.get(hits.size() - 1);
                    hits.set(hits.size() - 1, new RiskHit(last.ruleId(), last.severity(), last.round(),
                            last.traceId(), last.roundsHit(),
                            last.detail() + "（另有 " + extra + " 轮超长略）", last.evidenceJson()));
                }
                return hits;
            } catch (Exception e) {
                return List.of();
            }
        }
    }

    // ==================== TOOL-003 工具返回格式异常（按工具期望输出） ====================

    /**
     * V2（§4.8，low→medium）：查 expectedOutput[toolName]（缺省 TEXT_OR_JSON）——
     * JSON：先查堆栈特征，再强制 JSON 解析，解析失败 → 命中；TEXT_OR_JSON / TEXT：只查堆栈特征。
     * 合法 JSON（业务码失败归 TOOL-007）与无堆栈特征的 Markdown/纯文本不判。
     */
    static final class ToolResultMalformed implements DetectRule {

        static final Map<String, Object> DEFAULT_EXPECTED_OUTPUT = Map.of(
                "executeSql", "JSON", "execute_sql", "JSON", "Skill", "TEXT", "search_documents", "TEXT_OR_JSON");
        /** Exception: 起始行（如 java.sql.SQLException: ...） */
        static final Pattern EXCEPTION_LINE = Pattern.compile("(?m)^\\s*[\\w$.]*(Exception|Error)\\s*[(:]");

        @Override
        public String id() {
            return "TOOL-003";
        }

        @Override
        public String title() {
            return "工具返回格式异常";
        }

        @Override
        public String severity() {
            return "medium";
        }

        @Override
        public String description() {
            return "工具观察含异常堆栈（at xx.xxx( / Exception 起始行 / Traceback）；期望 JSON 的工具返回非合法 JSON";
        }

        @Override
        public Map<String, Object> defaultParams() {
            return Map.of("expectedOutput", DEFAULT_EXPECTED_OUTPUT);
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                Map<String, Object> expected = ctx.config().mapParam(id(), "expectedOutput", DEFAULT_EXPECTED_OUTPUT);
                List<RiskHit> hits = new ArrayList<>();
                for (DetectContext.TraceRow r : ctx.rounds()) {
                    for (DetectContext.ToolObservation o : ctx.observationsAfter(r.round())) {
                        String content = o.content() == null ? "" : o.content().trim();
                        if (content.isEmpty()) {
                            continue;
                        }
                        String tool = o.name() == null || o.name().isBlank() ? "tool" : o.name();
                        Object expRaw = expected.get(tool);
                        String expect = expRaw instanceof String s && !s.isBlank()
                                ? s.trim().toUpperCase() : "TEXT_OR_JSON";
                        boolean stack = hasStackFeature(content);
                        // ① 堆栈特征：任何期望类型都判
                        if (stack) {
                            hits.add(new RiskHit(id(), severity(), r.round(), r.traceId(), null,
                                    "第 " + r.round() + " 轮工具 " + tool + " 观察疑似异常堆栈："
                                            + abbreviate(content, 100),
                                    evidence(Map.of("round", r.round(), "tool", tool,
                                            "expected", expect, "kind", "stack",
                                            "excerpt", abbreviate(content, 200)))));
                            continue;
                        }
                        // ② 期望 JSON 的工具：强制解析，失败 → 命中
                        if ("JSON".equals(expect) && !isValidJson(content)) {
                            hits.add(new RiskHit(id(), severity(), r.round(), r.traceId(), null,
                                    "第 " + r.round() + " 轮工具 " + tool + " 期望 JSON 返回但解析失败："
                                            + abbreviate(content, 100),
                                    evidence(Map.of("round", r.round(), "tool", tool,
                                            "expected", "JSON", "kind", "broken-json",
                                            "excerpt", abbreviate(content, 200)))));
                        }
                        // TEXT_OR_JSON / TEXT：无堆栈特征即合法（Markdown/纯文本/合法 JSON 都不判）
                    }
                }
                return hits;
            } catch (Exception e) {
                return List.of();
            }
        }

        /** 堆栈特征：at 包名.类.方法( 模式 / Exception|Error 起始行 / Python Traceback */
        static boolean hasStackFeature(String content) {
            return content.contains("Traceback")
                    || RuleSupport.STACK_AT.matcher(content).find()
                    || EXCEPTION_LINE.matcher(content).find();
        }

        private boolean isValidJson(String content) {
            try {
                RuleSupport.MAPPER.readTree(content);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ==================== TOOL-004 SQL 非法操作（词法分析 V2-D7） ====================

    /**
     * V2（§4.9）：SQL 类工具的 sql 参数经词法预处理（剥注释/字符串字面量、WITH 跳 CTE）后
     * 首关键字 ∉ 只读白名单 {SELECT,WITH,EXPLAIN,ANALYZE,SHOW,TABLE} → 命中。
     * 字符串字面量里的 DELETE（WHERE remark='DELETE'）不再误报。写操作白名单用户豁免
     * （writeWhitelist，兼容读旧键 writeAllowedUsers）。high，一轮一条。
     */
    static final class SqlIllegalWrite implements DetectRule {

        @Override
        public String id() {
            return "TOOL-004";
        }

        @Override
        public String title() {
            return "SQL 非法操作";
        }

        @Override
        public String severity() {
            return "high";
        }

        @Override
        public String description() {
            return "SQL 类工具参数首关键字非只读（INSERT/UPDATE/DELETE/DROP…，词法分析剥注释与字符串字面量），白名单用户豁免";
        }

        @Override
        public Map<String, Object> defaultParams() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("sqlToolKeywords", List.of("sql"));
            params.put("writeWhitelist", List.of());
            return params;
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                DetectConfig cfg = ctx.config();
                List<String> sqlKeywords = cfg.listParam(id(), "sqlToolKeywords", List.of("sql"));
                List<String> whitelist = cfg.listParamCompat(id(), "writeWhitelist", "writeAllowedUsers", List.of());
                String userId = ctx.session() == null ? null : ctx.session().userId();
                if (userId != null && whitelist.stream().anyMatch(u -> u.equals(userId))) {
                    return List.of(); // 白名单用户允许写，豁免
                }
                List<RiskHit> hits = new ArrayList<>();
                for (DetectContext.TraceRow r : ctx.rounds()) {
                    for (DetectContext.ToolCallView call : ctx.toolCalls(r.round())) {
                        if (!isSqlTool(call.name(), sqlKeywords) || call.arguments() == null) {
                            continue;
                        }
                        String sql = sqlArgText(call.arguments());
                        if (sql == null || sql.isBlank()) {
                            continue;
                        }
                        String keyword = illegalWriteKeyword(sql);
                        if (keyword == null) {
                            continue;
                        }
                        hits.add(new RiskHit(id(), severity(), r.round(), r.traceId(), null,
                                "第 " + r.round() + " 轮工具 " + call.name() + " 的 SQL 首关键字为 "
                                        + keyword + "（非只读白名单）",
                                evidence(Map.of("round", r.round(), "tool", call.name(),
                                        "keyword", keyword,
                                        "sql", abbreviate(sql, 200)))));
                        break; // 一轮一条，聚合该轮首个命中调用
                    }
                }
                return hits;
            } catch (Exception e) {
                return List.of();
            }
        }

        /** 从 arguments 提取 sql 文本：优先 sql 字段，缺字段时整个 arguments 当 SQL（兜底） */
        static String sqlArgText(String arguments) {
            try {
                JsonNode node = MAPPER.readTree(arguments);
                if (node.isObject() && node.has("sql") && node.get("sql").isTextual()) {
                    return node.get("sql").textValue();
                }
            } catch (Exception ignored) {
                // fallthrough
            }
            return arguments;
        }
    }

    // ==================== TOOL-005 统计查询空结果风险提示 ====================

    /**
     * V2（§4.10，改名 + 重构，默认启用）：四个条件同时成立才命中——
     * ① 用户问题含统计类词；② 存在 success=true 的 SQL 工具轮（排除 TOOL-001 命中轮）；
     * ③ 该轮结果为空（rows:[] / count:0 / 空文本特征）；④ 最终回答给出确定性统计结论且未声明无数据。
     */
    static final class SqlEmptyStatResult implements DetectRule {

        static final List<String> DEFAULT_STAT_KEYWORDS =
                List.of("多少", "数量", "几个", "count", "统计", "总数", "合计");
        static final List<String> DEFAULT_EMPTY_PATTERNS = RuleSupport.EMPTY_RESULT_MARKERS;
        static final String DEFAULT_CLAIM_REGEX = "(共|总|为|是|约)\\s*[0-9]";
        static final List<String> DEFAULT_NO_DATA_WORDS =
                List.of("未查到", "无数据", "无法获取", "暂无", "没有找到", "没有查询到");

        @Override
        public String id() {
            return "TOOL-005";
        }

        @Override
        public String title() {
            return "统计查询空结果风险提示";
        }

        @Override
        public String severity() {
            return "low";
        }

        @Override
        public String description() {
            return "统计类问题 SQL 查询成功但结果为空，回答却给出确定性统计结论（0 条不一定是异常，仅提示）";
        }

        @Override
        public Map<String, Object> defaultParams() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("statKeywords", DEFAULT_STAT_KEYWORDS);
            params.put("emptyPatterns", DEFAULT_EMPTY_PATTERNS);
            params.put("claimRegex", DEFAULT_CLAIM_REGEX);
            params.put("noDataWords", DEFAULT_NO_DATA_WORDS);
            return params;
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                DetectConfig cfg = ctx.config();
                List<String> statKws = cfg.listParam(id(), "statKeywords", DEFAULT_STAT_KEYWORDS);
                List<String> emptyPatterns = cfg.listParam(id(), "emptyPatterns", DEFAULT_EMPTY_PATTERNS);
                List<String> noDataWords = cfg.listParam(id(), "noDataWords", DEFAULT_NO_DATA_WORDS);
                String claimRegex = cfg.stringParam(id(), "claimRegex", DEFAULT_CLAIM_REGEX);
                List<String> sqlKeywords = cfg.listParam("TOOL-004", "sqlToolKeywords", List.of("sql"));
                Set<Integer> tool001Rounds = ctx.hitRoundsOf("TOOL-001");

                // ① 统计类问题
                String question = ctx.question() == null ? "" : ctx.question().toLowerCase();
                boolean statQuestion = statKws.stream().anyMatch(kw -> !kw.isBlank()
                        && question.contains(kw.toLowerCase()));
                if (!statQuestion) {
                    return List.of();
                }
                // ②+③ 最后一个成功且未被 TOOL-001 命中的 SQL 轮，结果为空
                DetectContext.TraceRow target = null;
                List<DetectContext.ToolObservation> obs = List.of();
                for (int i = ctx.rounds().size() - 1; i >= 0; i--) {
                    DetectContext.TraceRow r = ctx.rounds().get(i);
                    if (!r.success() || tool001Rounds.contains(r.round())) {
                        continue;
                    }
                    if (!roundHasSqlTool(ctx, r, sqlKeywords)) {
                        continue;
                    }
                    List<DetectContext.ToolObservation> o = ctx.observationsAfter(r.round());
                    target = r;
                    obs = o;
                    break;
                }
                if (target == null || !isEmptyResult(obs)) {
                    return List.of(); // 结果非空不判
                }
                String marker = firstEmptyMarker(obs, emptyPatterns);
                // ④ 回答：确定性统计结论 + 未声明无数据
                String answer = ctx.answer();
                if (answer == null || answer.isBlank()
                        || !answer.matches("(?s).*(?:" + claimRegex + ").*")) {
                    return List.of();
                }
                for (String deny : noDataWords) {
                    if (deny != null && !deny.isBlank() && answer.contains(deny)) {
                        return List.of(); // 已声明无数据 → 不判
                    }
                }
                return List.of(new RiskHit(id(), severity(), target.round(), target.traceId(), null,
                        "统计类问题 SQL 查询成功但结果为空（" + abbreviate(marker, 20) + "），回答仍给出确定性统计结论",
                        evidence(Map.of("round", target.round(), "marker", marker,
                                "answerExcerpt", abbreviate(answer, 120)))));
            } catch (Exception e) {
                return List.of();
            }
        }

        /** 命中的空结果标记（配置词表优先，兜底内置特征；展示用） */
        private static String firstEmptyMarker(List<DetectContext.ToolObservation> obs,
                                               List<String> patterns) {
            for (DetectContext.ToolObservation o : obs) {
                String c = o.content() == null ? "" : o.content().replaceAll("\\s+", "");
                for (String m : patterns) {
                    if (!m.isBlank() && c.toLowerCase().contains(m.toLowerCase())) {
                        return m;
                    }
                }
            }
            return obs.isEmpty() || obs.stream().allMatch(o ->
                    o.content() == null || o.content().isBlank()) ? "空文本" : "rows:[]";
        }
    }

    // ==================== TOOL-006 工具调用参数异常（V2 新增，查输入） ====================

    /**
     * V2 新增（§4.11）：与 TOOL-003 查输出互补，检查调用入参——
     * ① requiredArgs 配置内工具必填参数缺失 → 命中；② 通用检查（全部工具）：参数值 null / 空串
     * / 空对象且必填 → 命中（未知工具查显式传 null/空串）；③ SQL 参数残缺启发
     * （尾部 SELECT/FROM/WHERE/AND/,/( 或缺 FROM 的裸标识符收尾 SELECT）→ 命中。
     */
    static final class ArgAnomaly implements DetectRule {

        static final Map<String, Object> DEFAULT_REQUIRED_ARGS = Map.of(
                "executeSql", List.of("sql"), "execute_sql", List.of("sql"), "Skill", List.of("name"));

        @Override
        public String id() {
            return "TOOL-006";
        }

        @Override
        public String title() {
            return "工具调用参数异常";
        }

        @Override
        public String severity() {
            return "high";
        }

        @Override
        public String description() {
            return "必填参数缺失或值为 null/空，SQL 参数残缺（以 SELECT/FROM/WHERE/AND/, 结尾或缺 FROM）";
        }

        @Override
        public Map<String, Object> defaultParams() {
            return Map.of("requiredArgs", DEFAULT_REQUIRED_ARGS);
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                DetectConfig cfg = ctx.config();
                Map<String, Object> requiredArgs = cfg.mapParam(id(), "requiredArgs", DEFAULT_REQUIRED_ARGS);
                List<String> sqlKeywords = cfg.listParam("TOOL-004", "sqlToolKeywords", List.of("sql"));
                List<RiskHit> hits = new ArrayList<>();
                for (DetectContext.TraceRow r : ctx.rounds()) {
                    for (DetectContext.ToolCallView call : ctx.toolCalls(r.round())) {
                        if (call.arguments() == null || call.arguments().isBlank()) {
                            continue;
                        }
                        JsonNode args = parseObject(call.arguments());
                        if (args == null) {
                            continue; // arguments 非 JSON 对象无法检查（漏报不误报）
                        }
                        String tool = call.name() == null || call.name().isBlank() ? "tool" : call.name();
                        List<String> required = requiredListOf(requiredArgs.get(tool));

                        // ①+② 必填参数缺失 / 必填参数空值 / 显式 null 或空串参数
                        String problem = null;
                        if (required != null) {
                            for (String req : required) {
                                JsonNode v = args.get(req);
                                if (v == null || v.isMissingNode()) {
                                    problem = "必填参数 " + req + " 缺失";
                                    break;
                                }
                                String blank = blankValue(v);
                                if (blank != null) {
                                    problem = "必填参数 " + req + " 为" + blank;
                                    break;
                                }
                            }
                        } else {
                            // 未知工具通用检查：显式传 null / 空串
                            var fields = args.fields();
                            while (fields.hasNext() && problem == null) {
                                var e = fields.next();
                                String blank = blankValue(e.getValue());
                                if (blank != null && "空值".equals(blank)) {
                                    problem = "参数 " + e.getKey() + " 为 null";
                                } else if (blank != null && "空串".equals(blank)) {
                                    problem = "参数 " + e.getKey() + " 为空串";
                                }
                            }
                        }
                        // ③ SQL 参数残缺启发
                        if (problem == null && isSqlTool(tool, sqlKeywords)) {
                            JsonNode sqlNode = args.get("sql");
                            if (sqlNode != null && sqlNode.isTextual()
                                    && isTruncatedSql(sqlNode.textValue())) {
                                problem = "SQL 参数残缺（"
                                        + abbreviate(sqlNode.textValue(), 60) + "…）";
                            }
                        }
                        if (problem != null) {
                            hits.add(new RiskHit(id(), severity(), r.round(), r.traceId(), null,
                                    "第 " + r.round() + " 轮工具 " + tool + " " + problem,
                                    evidence(Map.of("round", r.round(), "tool", tool,
                                            "problem", problem,
                                            "arguments", abbreviate(call.arguments(), 160)))));
                            break; // 一轮一条
                        }
                    }
                }
                return hits;
            } catch (Exception e) {
                return List.of();
            }
        }

        @SuppressWarnings("unchecked")
        private static List<String> requiredListOf(Object raw) {
            if (!(raw instanceof List<?> list)) {
                return null; // 未配置的工具（未知工具）
            }
            return (List<String>) list;
        }

        /** 空值类型：null → "空值"；空串 → "空串"；空对象 → "空对象"；非空返回 null */
        private static String blankValue(JsonNode v) {
            if (v == null || v.isNull()) {
                return "空值";
            }
            if (v.isTextual() && v.textValue().isBlank()) {
                return "空串";
            }
            if (v.isObject() && v.isEmpty()) {
                return "空对象";
            }
            return null;
        }

        private static JsonNode parseObject(String raw) {
            try {
                JsonNode node = MAPPER.readTree(raw);
                return node.isObject() ? node : null;
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ==================== TOOL-007 API 业务执行失败（V2 新增） ====================

    /**
     * V2 新增（§4.12）：apiTools 配置内工具的 Observation 解析 JSON 后取 codeField 值，
     * ∉ successCodes → 命中（detail 带 messageField 内容）。非配置工具不检查；
     * Observation 非 JSON → 交给 TOOL-003。apiTools 默认空 = 规则不触发（与业务平台对齐码约定后配置）。
     */
    static final class ApiBizFail implements DetectRule {

        @Override
        public String id() {
            return "TOOL-007";
        }

        @Override
        public String title() {
            return "API 业务执行失败";
        }

        @Override
        public String severity() {
            return "high";
        }

        @Override
        public String description() {
            return "API 工具返回业务码不在成功清单（apiTools 按工具配置 codeField/successCodes/messageField）";
        }

        @Override
        public Map<String, Object> defaultParams() {
            return Map.of("apiTools", Map.of());
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                Map<String, Object> apiTools = ctx.config().mapParam(id(), "apiTools");
                if (apiTools.isEmpty()) {
                    return List.of(); // 未配置业务码约定 → 不触发
                }
                List<RiskHit> hits = new ArrayList<>();
                for (DetectContext.TraceRow r : ctx.rounds()) {
                    for (DetectContext.ToolObservation o : ctx.observationsAfter(r.round())) {
                        Map<String, Object> conf = apiTools.get(o.name()) instanceof Map<?, ?> m
                                ? castConf(m) : null;
                        if (conf == null) {
                            continue; // 非配置的 API 工具不检查
                        }
                        JsonNode node;
                        try {
                            String content = o.content() == null ? "" : o.content().trim();
                            node = content.startsWith("{") ? MAPPER.readTree(content) : null;
                        } catch (Exception e) {
                            node = null;
                        }
                        if (node == null || !node.isObject()) {
                            continue; // 非 JSON → TOOL-003 管
                        }
                        String codeField = str(conf.get("codeField"), "code");
                        List<Object> successCodes = conf.get("successCodes") instanceof List<?> l
                                ? new ArrayList<>(l) : List.<Object>of(0, 200);
                        JsonNode code = node.get(codeField);
                        if (code == null || code.isNull()) {
                            continue; // 无业务码字段 → 约定不符，不判（漏报不误报）
                        }
                        if (isSuccessCode(code, successCodes)) {
                            continue;
                        }
                        String messageField = str(conf.get("messageField"), "message");
                        String message = node.get(messageField) == null || node.get(messageField).isNull()
                                ? "" : node.get(messageField).asText();
                        hits.add(new RiskHit(id(), severity(), r.round(), r.traceId(), null,
                                "第 " + r.round() + " 轮 API 工具 " + o.name() + " 业务失败（"
                                        + codeField + "=" + code.asText() + "）"
                                        + (message.isBlank() ? "" : "：" + abbreviate(message, 80)),
                                evidence(Map.of("round", r.round(), "tool", o.name(),
                                        "code", code.asText(), "message", abbreviate(message, 120),
                                        "excerpt", abbreviate(o.content() == null ? "" : o.content(), 160)))));
                        break; // 一轮一条
                    }
                }
                return hits;
            } catch (Exception e) {
                return List.of();
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> castConf(Map<?, ?> m) {
            return (Map<String, Object>) m;
        }

        private static String str(Object v, String def) {
            return v instanceof String s && !s.isBlank() ? s : def;
        }

        /** 业务码比对：数值比数值，文本比文本（大小写敏感） */
        private static boolean isSuccessCode(JsonNode code, List<Object> successCodes) {
            for (Object sc : successCodes) {
                if (sc instanceof Number n && code.isNumber()) {
                    if (n.doubleValue() == code.doubleValue()) {
                        return true;
                    }
                } else if (sc != null && String.valueOf(sc).equals(code.asText())) {
                    return true;
                }
            }
            return false;
        }
    }
}
