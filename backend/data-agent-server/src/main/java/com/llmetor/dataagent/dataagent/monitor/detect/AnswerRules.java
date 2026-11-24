package com.llmetor.dataagent.dataagent.monitor.detect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.MAPPER;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.abbreviate;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.evidence;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.isEmptyResult;

/**
 * 最终回答规则 2 条（V2 规格 §4.14/4.15）：失败后仍给出确定性结论（恢复感知 V2-D4）/
 * 工具结果与最终答案数值不一致（真数值比对 V2-D3）。
 * ANSWER-001 依赖规则间命中（TOOL-001 / REACT-002），注册顺序必须在它们之后。
 */
final class AnswerRules {

    private AnswerRules() {
    }

    static List<DetectRule> all() {
        return List.of(new ConfidentAfterFailure(), new NumberMismatch());
    }

    // ==================== ANSWER-001 工具失败后仍给出确定性结论（恢复感知） ====================

    /**
     * V2（§4.14）：存在工具失败（TOOL-001/REACT-002 命中或独立扫 success=false 轮），
     * 且<b>最后一个失败轮之后不存在成功的数据来源轮</b>（success=true 的 SQL/API 工具轮且结果非空
     * ——存在则数据可能来自恢复后的调用，不命中），且回答含确定性数字结论且未声明获取失败 → 命中。
     */
    static final class ConfidentAfterFailure implements DetectRule {

        static final String DEFAULT_DETERMINISTIC_REGEX =
                "(共[计有]?|总共|总计|合计|一共|约为?|是)\\s*[0-9]+(?:\\.[0-9]+)?";
        static final List<String> DEFAULT_DENY_WORDS = List.of("无法", "失败", "未能", "报错", "错误",
                "未查到", "无数据", "暂无");

        @Override
        public String id() {
            return "ANSWER-001";
        }

        @Override
        public String title() {
            return "失败后仍给出确定性结论";
        }

        @Override
        public String severity() {
            return "high";
        }

        @Override
        public String description() {
            return "存在工具失败且失败后无成功数据轮兜底，但最终回答仍含确定性数字结论且未声明获取失败（结论不可信）";
        }

        @Override
        public Map<String, Object> defaultParams() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("deterministicRegex", DEFAULT_DETERMINISTIC_REGEX);
            params.put("denyWords", DEFAULT_DENY_WORDS);
            return params;
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                // ① 存在工具失败（规则命中 或 独立扫 success=false 轮，口径与 V1 一致）
                List<DetectContext.TraceRow> failedRounds = ctx.rounds().stream()
                        .filter(r -> !r.success()).toList();
                boolean hasFailure = ctx.hasHit("TOOL-001", "REACT-002") || !failedRounds.isEmpty();
                if (!hasFailure) {
                    return List.of();
                }
                // ② 最后失败轮之后存在成功数据来源轮 → 数据可能来自恢复后的调用，不命中（V2-D4）
                int lastFailRound = lastFailureRound(ctx, failedRounds);
                if (hasSuccessDataRoundAfter(ctx, lastFailRound)) {
                    return List.of();
                }
                // ③+④ 回答：确定性数字结论 + 未声明失败/无数据
                String answer = ctx.answer();
                if (answer == null || answer.isBlank()) {
                    return List.of();
                }
                DetectConfig cfg = ctx.config();
                List<String> denyWords = cfg.listParam(id(), "denyWords", DEFAULT_DENY_WORDS);
                for (String deny : denyWords) {
                    if (deny != null && !deny.isBlank() && answer.contains(deny)) {
                        return List.of(); // 已声明失败/无法获取 → 不算确定性结论
                    }
                }
                String regex = cfg.stringParam(id(), "deterministicRegex", DEFAULT_DETERMINISTIC_REGEX);
                Matcher matcher;
                try {
                    matcher = Pattern.compile(regex).matcher(answer);
                } catch (Exception e) {
                    matcher = Pattern.compile(DEFAULT_DETERMINISTIC_REGEX).matcher(answer);
                }
                if (!matcher.find()) {
                    return List.of();
                }
                List<String> hitRules = ctx.hitsByRule().keySet().stream()
                        .filter(rid -> rid.equals("TOOL-001") || rid.equals("REACT-002"))
                        .toList();
                DetectContext.TraceRow last = ctx.lastRound();
                String matched = matcher.group();
                return List.of(new RiskHit(id(), severity(),
                        last == null ? null : last.round(), last == null ? null : last.traceId(), null,
                        "存在工具失败（" + (hitRules.isEmpty() ? "success=false 轮" : String.join("/", hitRules))
                                + "）且失败后无成功数据轮，但最终回答仍给出确定性结论（"
                                + abbreviate(matched, 30) + "…）",
                        evidence(Map.of("matched", matched, "hitRules", hitRules,
                                "lastFailRound", lastFailRound,
                                "answerExcerpt", abbreviate(answer, 160)))));
            } catch (Exception e) {
                return List.of();
            }
        }

        /** 最后失败轮号：success=false 轮与 TOOL-001/REACT-002 命中定位轮的最大值；无 → 0 */
        private static int lastFailureRound(DetectContext ctx, List<DetectContext.TraceRow> failedRounds) {
            int last = 0;
            for (DetectContext.TraceRow r : failedRounds) {
                last = Math.max(last, r.round());
            }
            for (String ruleId : List.of("TOOL-001", "REACT-002")) {
                for (Integer round : ctx.hitRoundsOf(ruleId)) {
                    last = Math.max(last, round);
                }
            }
            return last;
        }

        /**
         * lastFailRound 之后是否存在成功的数据来源轮（success=true 的 SQL/API 工具轮且结果非空）。
         * SQL 工具按 sqlToolKeywords 识别；API 工具按 TOOL-007 apiTools 配置键识别。
         */
        private static boolean hasSuccessDataRoundAfter(DetectContext ctx, int lastFailRound) {
            DetectConfig cfg = ctx.config();
            List<String> sqlKeywords = cfg.listParam("TOOL-004", "sqlToolKeywords", List.of("sql"));
            Set<String> apiTools = cfg.mapParam("TOOL-007", "apiTools").keySet();
            for (DetectContext.TraceRow r : ctx.rounds()) {
                if (r.round() <= lastFailRound || !r.success()) {
                    continue;
                }
                boolean dataSource = ctx.toolCalls(r.round()).stream().anyMatch(c ->
                        (c.name() != null && ToolRules.isSqlTool(c.name(), sqlKeywords))
                                || (c.name() != null && apiTools.contains(c.name())));
                if (!dataSource) {
                    continue;
                }
                if (!isEmptyResult(ctx.observationsAfter(r.round()))) {
                    return true; // 恢复后的成功数据轮
                }
            }
            return false;
        }
    }

    // ==================== ANSWER-002 工具结果与最终答案数值不一致（真数值比对） ====================

    /**
     * V2（§4.15，V2-D3 真数值比对）：取最后一次成功统计结果的单一数字字段值 T（statFields 命中，
     * 多字段/多行聚合跳过不判）——T 出现在回答中 → 不命中；T 未出现但回答含「统计结论模式」的
     * 其他数字（共 X 个 / 为 N 等，约数不算）→ 命中；回答无数值结论（定性）→ 不命中。
     * 「数字未出现」本身不再告警（V1 误报主场景修正）。statFields 兼容读旧键 statKeys。
     */
    static final class NumberMismatch implements DetectRule {

        static final List<String> DEFAULT_STAT_FIELDS =
                List.of("count", "total", "sum", "cnt", "num", "数量", "总数", "合计");
        /** 结论数字模式（捕获组 1 = 数字；支持千分位；约数前置词不触发） */
        static final List<String> DEFAULT_CONCLUSION_PATTERNS = List.of(
                "(?:共[计有]?|总共|总计|合计|一共)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:个|项|条|次|人|家|台|笔|万元|亿元|元)",
                "(?<!约)(?<!大概)(?<!超)(?<!近)([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:个|项|条|次|人|家|台|笔)",
                "(?:为|是|达|达到)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)(?:\\s*(?:万元|亿元|元|%|个|项|条|次))?");
        /** 回答数字提取（含千分位 1,234 归一） */
        static final Pattern ANSWER_NUMBER =
                Pattern.compile("-?[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?|-?[0-9]+(?:\\.[0-9]+)?");
        /** JSON 数值字段（key ≤30 字符，值为纯数字） */
        static final Pattern NUM_FIELD = Pattern.compile("\"([^\"]{1,30})\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");

        @Override
        public String id() {
            return "ANSWER-002";
        }

        @Override
        public String title() {
            return "工具结果与最终答案数值不一致";
        }

        @Override
        public String severity() {
            return "medium";
        }

        @Override
        public String description() {
            return "最后一次成功统计结果为 T，回答的统计结论却给出其他数字（定性回答与约数不判）";
        }

        @Override
        public Map<String, Object> defaultParams() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("statFields", DEFAULT_STAT_FIELDS);
            params.put("conclusionPatterns", DEFAULT_CONCLUSION_PATTERNS);
            return params;
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                String answer = ctx.answer();
                if (answer == null || answer.isBlank()) {
                    return List.of();
                }
                DetectConfig cfg = ctx.config();
                List<String> statFields = cfg.listParamCompat(id(), "statFields", "statKeys", DEFAULT_STAT_FIELDS);
                List<String> conclusionPatterns =
                        cfg.listParam(id(), "conclusionPatterns", DEFAULT_CONCLUSION_PATTERNS);

                Set<Double> answerNumbers = extractAnswerNumbers(answer);

                // 从后往前找最后一次成功的统计型观察（单一数字字段）
                for (int i = ctx.rounds().size() - 1; i >= 0; i--) {
                    DetectContext.TraceRow r = ctx.rounds().get(i);
                    if (!r.success() || ctx.observationsAfter(r.round()).isEmpty()) {
                        continue;
                    }
                    for (DetectContext.ToolObservation o : ctx.observationsAfter(r.round())) {
                        Double t = singleStatValue(o.content(), statFields);
                        if (t == null) {
                            continue; // 非统计型/多字段 → 本版跳过
                        }
                        // T 出现在回答中 → 一致
                        for (double n : answerNumbers) {
                            if (Math.abs(n - t) < 1e-9) {
                                return List.of();
                            }
                        }
                        // T 未出现：找统计结论模式下的冲突数字（约数已被模式排除）
                        Double conflict = conclusionNumber(answer, conclusionPatterns, t);
                        if (conflict == null) {
                            return List.of(); // 回答无数值结论（定性）→ 不判
                        }
                        String tool = o.name() == null || o.name().isBlank() ? "tool" : o.name();
                        return List.of(new RiskHit(id(), severity(), r.round(), r.traceId(), null,
                                "工具 " + tool + " 返回统计值 " + fmt(t) + "，最终回答结论却为 "
                                        + fmt(conflict) + "（数值不一致）",
                                evidence(Map.of("round", r.round(), "tool", tool,
                                        "toolValue", fmt(t), "answerValue", fmt(conflict),
                                        "answerNumbers",
                                        answerNumbers.stream().map(AnswerRules::fmt).toList(),
                                        "excerpt", abbreviate(o.content() == null ? "" : o.content(), 160)))));
                    }
                }
                return List.of(); // 全链无统计型结果 → 不判
            } catch (Exception e) {
                return List.of();
            }
        }

        /** 回答数字集合（千分位归一；上限 50 个防异常长文） */
        private static Set<Double> extractAnswerNumbers(String answer) {
            Set<Double> out = new LinkedHashSet<>();
            Matcher am = ANSWER_NUMBER.matcher(answer);
            while (am.find() && out.size() < 50) {
                try {
                    out.add(Double.parseDouble(am.group().replace(",", "")));
                } catch (NumberFormatException ignored) {
                    // 超长数字串解析失败忽略
                }
            }
            return out;
        }

        /**
         * 单一数字字段统计值：JSON 观察里 statFields 命中的数字字段恰 1 个且整体字段数 ≤3，
         * 多字段/多行聚合场景明确跳过（首版只做单数字）。不满足返回 null。
         */
        private static Double singleStatValue(String content, List<String> statFields) {
            try {
                String t = content == null ? "" : content.trim();
                if (!t.startsWith("{")) {
                    return null;
                }
                Matcher m = NUM_FIELD.matcher(t);
                String statKey = null;
                double value = 0;
                int totalFields = 0;
                int statHits = 0;
                while (m.find()) {
                    totalFields++;
                    String key = m.group(1);
                    String keyLower = key.toLowerCase();
                    if (statFields.stream().anyMatch(k -> !k.isBlank() && keyLower.contains(k.toLowerCase()))) {
                        statHits++;
                        if (statKey == null) {
                            statKey = key;
                            value = Double.parseDouble(m.group(2));
                        }
                    }
                }
                if (statHits != 1 || totalFields > 3) {
                    return null;
                }
                return value;
            } catch (Exception e) {
                return null;
            }
        }

        /** 回答里按结论模式提取的数字：命中且 ≠ T 的第一个冲突数；无结论模式数字返回 null */
        private static Double conclusionNumber(String answer, List<String> patterns, double t) {
            for (String regex : patterns) {
                Matcher m;
                try {
                    m = Pattern.compile(regex).matcher(answer);
                } catch (Exception e) {
                    continue;
                }
                while (m.find()) {
                    try {
                        double n = Double.parseDouble(m.group(1).replace(",", ""));
                        if (Math.abs(n - t) >= 1e-9) {
                            return n;
                        }
                    } catch (Exception ignored) {
                        // 数字组解析失败跳过
                    }
                }
            }
            return null;
        }
    }

    /** 数字展示：去尾零（86.0 → 86） */
    static String fmt(double value) {
        try {
            String s = MAPPER.writeValueAsString(value);
            return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
