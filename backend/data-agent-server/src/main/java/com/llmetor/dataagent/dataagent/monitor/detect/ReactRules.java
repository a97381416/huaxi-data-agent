package com.llmetor.dataagent.dataagent.monitor.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.abbreviate;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.argsHash;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.callSignature;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.evidence;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.isEmptyResult;
import static com.llmetor.dataagent.dataagent.monitor.detect.RuleSupport.resultFingerprint;

/**
 * REACT 执行链路规则 5 条（V2 规格 §4.1~4.5）：循环过多 / 连续失败轮（恢复感知） /
 * 相同调用重复且无进展 / 最终回答缺失 / 多轮无有效进展。
 * 数据源以瘦表为主（REACT-003/005 需 payload 的 tool_calls 与次轮观察）。
 */
final class ReactRules {

    private ReactRules() {
    }

    static List<DetectRule> all() {
        return List.of(new LoopTooMany(), new ConsecutiveFails(), new RepeatToolCall(),
                new AnswerMissing(), new NoProgress());
    }

    // ==================== REACT-001 执行循环过多 ====================

    /**
     * 决策轮数（trace 行数）> maxRounds（V2 默认 15→10，观察级）；medium，定位末轮。
     * 证据同时给「决策轮 X / 工具调用 Y 次」两数——调用次数多不单独触发（V2 规格 §4.1）。
     */
    static final class LoopTooMany implements DetectRule {
        @Override
        public String id() {
            return "REACT-001";
        }

        @Override
        public String title() {
            return "执行循环过多";
        }

        @Override
        public String severity() {
            return "medium";
        }

        @Override
        public String description() {
            return "提问执行轮数超过阈值（可能是无效循环或问题拆解失败）";
        }

        @Override
        public Map<String, Object> defaultParams() {
            return Map.of("maxRounds", 10);
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                int max = ctx.config().intParam(id(), "maxRounds", 10);
                int decisionRounds = ctx.rounds().size(); // 决策轮数 = trace 行数（V2 口径）
                DetectContext.TraceRow last = ctx.lastRound();
                if (decisionRounds <= max || last == null) {
                    return List.of();
                }
                // 工具调用次数 = tool 轮的调用总数（一轮多工具计多次；仅证据展示，不参与触发）
                int callCount = 0;
                for (DetectContext.TraceRow r : ctx.rounds()) {
                    callCount += ctx.toolCalls(r.round()).size();
                }
                String detail = "执行决策轮 " + decisionRounds + " 轮（超过阈值 " + max
                        + " 轮），工具调用 " + callCount + " 次";
                return List.of(new RiskHit(id(), severity(), last.round(), last.traceId(), null,
                        detail,
                        evidence(Map.of("decisionRounds", decisionRounds, "toolCalls", callCount,
                                "maxRounds", max))));
            } catch (Exception e) {
                return List.of();
            }
        }
    }

    // ==================== REACT-002 连续工具执行失败（恢复感知） ====================

    /**
     * V2（§4.2）：连续失败 ≥ failStreak（默认 2）轮 <b>且之后到结束再无任何成功轮</b>才命中——
     * 失败后换工具/改参成功（恢复）不判。只可能最后一段失败段未恢复，出一条命中，定位段首轮。
     */
    static final class ConsecutiveFails implements DetectRule {

        @Override
        public String id() {
            return "REACT-002";
        }

        @Override
        public String title() {
            return "连续工具执行失败";
        }

        @Override
        public String severity() {
            return "high";
        }

        @Override
        public String description() {
            return "连续多轮执行失败且直到结束未恢复（失败后重试成功的不算）";
        }

        @Override
        public Map<String, Object> defaultParams() {
            return Map.of("failStreak", 2);
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                int streak = ctx.config().intParam(id(), "failStreak", 2);
                List<DetectContext.TraceRow> rounds = ctx.rounds();
                // 找最后一个失败轮：它之后若还有成功轮 = 已恢复，不判（V2 恢复感知）
                int lastFail = -1;
                for (int i = rounds.size() - 1; i >= 0; i--) {
                    if (!rounds.get(i).success()) {
                        lastFail = i;
                        break;
                    }
                }
                if (lastFail < 0 || lastFail != rounds.size() - 1) {
                    return List.of(); // 无失败，或失败后已恢复成功 → 不命中
                }
                // 未恢复：向前扩成连续失败段，段长 ≥ failStreak 才判
                int segStart = lastFail;
                while (segStart > 0 && !rounds.get(segStart - 1).success()) {
                    segStart--;
                }
                int len = lastFail - segStart + 1;
                if (len < streak) {
                    return List.of(); // 末段失败不够长 → 不命中
                }
                DetectContext.TraceRow first = rounds.get(segStart);
                DetectContext.TraceRow last = rounds.get(lastFail);
                String firstError = first.errorMessage() == null ? "" : first.errorMessage();
                return List.of(new RiskHit(id(), severity(), first.round(), first.traceId(),
                        ReactRules.roundsCsv(rounds, segStart, lastFail),
                        "第 " + first.round() + "~" + last.round() + " 轮连续失败，此后未再成功（未恢复）"
                                + (firstError.isBlank() ? "" : "：" + abbreviate(firstError, 100)),
                        evidence(Map.of("rounds", roundNums(rounds, segStart, lastFail),
                                "recovered", false,
                                "firstError", abbreviate(firstError, 200)))));
            } catch (Exception e) {
                return List.of();
            }
        }
    }

    // ==================== REACT-003 相同工具重复调用且无进展 ====================

    /**
     * V2（§4.3）：调用指纹（name+参数归一化哈希）连续 ≥ repeatCalls 次，<b>且各轮结果指纹完全
     * 相同</b>（无有效进展）才命中——结果在变（分页推进/数据变化）不判；severity high→medium。
     */
    static final class RepeatToolCall implements DetectRule {
        @Override
        public String id() {
            return "REACT-003";
        }

        @Override
        public String title() {
            return "相同工具重复调用";
        }

        @Override
        public String severity() {
            return "medium";
        }

        @Override
        public String description() {
            return "相同工具相同参数连续调用多次且结果无任何变化（无效循环；改参重试或结果在变不算）";
        }

        @Override
        public Map<String, Object> defaultParams() {
            return Map.of("repeatCalls", 3);
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                int need = ctx.config().intParam(id(), "repeatCalls", 3);
                List<DetectContext.TraceRow> rounds = ctx.rounds();
                List<RiskHit> hits = new ArrayList<>();
                String runSig = null;
                List<DetectContext.TraceRow> run = new ArrayList<>();
                for (DetectContext.TraceRow r : rounds) {
                    List<DetectContext.ToolCallView> calls = ctx.toolCalls(r.round());
                    String sig = calls.isEmpty() ? null : callSignature(calls);
                    if (sig == null || !sig.equals(runSig)) {
                        flushRun(ctx, hits, run, need);
                        runSig = sig;
                        run = new ArrayList<>();
                    }
                    if (sig != null) {
                        run.add(r);
                    }
                }
                flushRun(ctx, hits, run, need);
                return hits;
            } catch (Exception e) {
                return List.of();
            }
        }

        /**
         * 收尾一段相同签名轮次：长度达标且「可观测」结果指纹全部相同才出一条命中
         * （末轮无次轮观察属会话自然收尾，不算结果变化；全部轮次观察缺失 = 无证据，
         * 退回 V1 仅签名字段判定；任一可观测结果不同 → 不判）。
         */
        private void flushRun(DetectContext ctx, List<RiskHit> hits,
                              List<DetectContext.TraceRow> run, int need) {
            if (run.size() < need) {
                return;
            }
            // V2：可观测结果指纹须全部相同；结果在变（分页推进/数据变化）→ 不判
            String fp0 = null;
            boolean anyObserved = false;
            for (DetectContext.TraceRow r : run) {
                List<DetectContext.ToolObservation> obs = ctx.observationsAfter(r.round());
                if (obs.isEmpty()) {
                    continue; // 无观察（会话收尾/读取失败）不参与比对
                }
                String fp = resultFingerprint(obs);
                if (!anyObserved) {
                    fp0 = fp;
                    anyObserved = true;
                } else if (!fp.equals(fp0)) {
                    return;
                }
            }
            DetectContext.TraceRow first = run.get(0);
            List<DetectContext.ToolCallView> firstCalls = ctx.toolCalls(first.round());
            String toolName = firstCalls.isEmpty() ? firstToolName(first) : firstCalls.get(0).name();
            String hash = firstCalls.isEmpty() ? "-" : argsHash(firstCalls.get(0).arguments());
            List<Integer> nums = new ArrayList<>();
            for (DetectContext.TraceRow r : run) {
                if (nums.size() < 10) {
                    nums.add(r.round());
                }
            }
            hits.add(new RiskHit(id(), severity(), first.round(), first.traceId(), roundsCsv(run),
                    "工具 " + toolName + " 参数哈希 " + hash + " 连续 " + run.size()
                            + " 轮相同且结果无变化",
                    evidence(Map.of("tool", toolName, "argsHash", hash,
                            "resultUnchanged", true,
                            "rounds", nums, "repeatCalls", need))));
        }

        private String firstToolName(DetectContext.TraceRow row) {
            if (row.toolNames() != null && !row.toolNames().isEmpty()) {
                return row.toolNames().get(0);
            }
            return "tool";
        }
    }

    // ==================== REACT-004 最终回答缺失 ====================

    /** status=completed 且（answer 为空 或 无成功 text 轮）；medium。V1 逻辑保持（§4.4） */
    static final class AnswerMissing implements DetectRule {
        @Override
        public String id() {
            return "REACT-004";
        }

        @Override
        public String title() {
            return "最终回答缺失";
        }

        @Override
        public String severity() {
            return "medium";
        }

        @Override
        public String description() {
            return "提问已完成但最终回答为空，或全程没有成功的文本轮";
        }

        @Override
        public Map<String, Object> defaultParams() {
            return Map.of();
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                DetectContext.SessionRow session = ctx.session();
                if (session == null || !"completed".equals(session.status())) {
                    return List.of();
                }
                boolean hasTextRound = ctx.rounds().stream()
                        .anyMatch(r -> r.success() && "text".equals(r.roundType()));
                String answer = ctx.answer();
                boolean answerBlank = answer == null || answer.isBlank();
                if (!answerBlank && hasTextRound) {
                    return List.of();
                }
                String detail = answerBlank ? "提问已完成但最终回答为空" : "提问已完成但全程没有成功的文本轮";
                DetectContext.TraceRow last = ctx.lastRound();
                return List.of(new RiskHit(id(), severity(),
                        last == null ? null : last.round(), last == null ? null : last.traceId(), null,
                        detail, evidence(Map.of("answerBlank", answerBlank, "hasTextRound", hasTextRound))));
            } catch (Exception e) {
                return List.of();
            }
        }
    }

    // ==================== REACT-005 多轮执行无有效进展（V2 新增） ====================

    /**
     * V2 新增（§4.5，与 REACT-003 互补）：滑动窗口连续 noProgressRounds（默认 3）个工具轮——
     * ① 各轮结果指纹全部相同（含全空）且调用指纹在变（REACT-003 的同指纹场景归 003）；或
     * ② 各轮观察均为空结果特征（rows:[] / count:0 / 空文本）且调用指纹在变。
     * 窗口内任一轮结果不同即断；调用指纹全同归 REACT-003 管，本规则去重不判。
     */
    static final class NoProgress implements DetectRule {

        @Override
        public String id() {
            return "REACT-005";
        }

        @Override
        public String title() {
            return "多轮执行无有效进展";
        }

        @Override
        public String severity() {
            return "medium";
        }

        @Override
        public String description() {
            return "连续多轮调用在变但结果始终相同或持续为空（换工具/换参数也拿不到新数据）";
        }

        @Override
        public Map<String, Object> defaultParams() {
            return Map.of("noProgressRounds", 3);
        }

        @Override
        public boolean enabled(DetectConfig cfg) {
            return cfg.enabled(id(), this);
        }

        @Override
        public List<RiskHit> eval(DetectContext ctx) {
            try {
                int need = ctx.config().intParam(id(), "noProgressRounds", 3);
                List<DetectContext.TraceRow> rounds = ctx.rounds();
                List<RiskHit> hits = new ArrayList<>();
                int i = 0;
                while (i <= rounds.size() - need) {
                    // 窗口须为 need 个相邻轮且全是工具轮（text/error 轮断窗）
                    boolean windowTool = true;
                    for (int k = i; k < i + need; k++) {
                        if (ctx.toolCalls(rounds.get(k).round()).isEmpty()) {
                            windowTool = false;
                            break;
                        }
                    }
                    if (!windowTool) {
                        i++;
                        continue;
                    }
                    RiskHit hit = evalWindow(ctx, rounds.subList(i, i + need), need);
                    if (hit != null) {
                        hits.add(hit);
                        i += need; // 命中的窗口整体跳过（不重叠出卡）
                    } else {
                        i++;
                    }
                }
                return hits;
            } catch (Exception e) {
                return List.of();
            }
        }

        /** 单窗口判定：调用在变 + （可观测结果指纹全同 或 全为空结果）→ 命中；全部轮无观察 → 无证据不判 */
        private RiskHit evalWindow(DetectContext ctx, List<DetectContext.TraceRow> window, int need) {
            // 调用指纹全同 → REACT-003 的管辖范围，本规则去重不判
            String sig0 = callSignature(ctx.toolCalls(window.get(0).round()));
            boolean callsVary = false;
            for (int k = 1; k < window.size(); k++) {
                if (!callSignature(ctx.toolCalls(window.get(k).round())).equals(sig0)) {
                    callsVary = true;
                    break;
                }
            }
            if (!callsVary) {
                return null;
            }
            // 可观测轮（有次轮观察）的结果指纹；末轮会话收尾无观察不参与比对
            String fp0 = null;
            boolean anyObserved = false;
            boolean fpAllDiffer = false;
            List<DetectContext.ToolObservation> obsAll = new ArrayList<>();
            for (DetectContext.TraceRow r : window) {
                List<DetectContext.ToolObservation> obs = ctx.observationsAfter(r.round());
                obsAll.addAll(obs);
                if (obs.isEmpty()) {
                    continue;
                }
                String fp = resultFingerprint(obs);
                if (!anyObserved) {
                    fp0 = fp;
                    anyObserved = true;
                } else if (!fp.equals(fp0)) {
                    fpAllDiffer = true;
                }
            }
            if (!anyObserved) {
                return null; // 整窗无观察证据，漏报不误报
            }
            boolean fpAllSame = !fpAllDiffer;
            boolean allEmpty = isEmptyResult(obsAll);
            if (!fpAllSame && !allEmpty) {
                return null; // 期间结果不同且有非空轮 → 有进展，不判
            }
            String kind = fpAllSame ? "result-unchanged" : "empty-loop";
            List<Integer> nums = new ArrayList<>();
            for (DetectContext.TraceRow r : window) {
                nums.add(r.round());
            }
            String firstTool = firstToolNameOf(ctx, window.get(0));
            String detail = "第 " + window.get(0).round() + "~"
                    + window.get(window.size() - 1).round() + " 轮调用持续变化但"
                    + (fpAllSame ? "结果完全相同" : "结果持续为空") + "，无有效进展";
            return new RiskHit(id(), severity(), window.get(0).round(), window.get(0).traceId(),
                    roundsCsv(window),
                    detail,
                    evidence(Map.of("rounds", nums, "kind", kind,
                            "sampleTool", firstTool, "noProgressRounds", need)));
        }

        private String firstToolNameOf(DetectContext ctx, DetectContext.TraceRow row) {
            List<DetectContext.ToolCallView> calls = ctx.toolCalls(row.round());
            if (!calls.isEmpty() && calls.get(0).name() != null && !calls.get(0).name().isBlank()) {
                return calls.get(0).name();
            }
            return row.toolNames() == null || row.toolNames().isEmpty()
                    ? "tool" : row.toolNames().get(0);
        }
    }

    // ==================== 共用 ====================

    /** 涉及轮清单 "5,6,7"（上限 10 个，超出补 …） */
    static String roundsCsv(List<DetectContext.TraceRow> rows, int from, int to) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int k = from; k <= to && count < 10; k++, count++) {
            if (count > 0) {
                sb.append(',');
            }
            sb.append(rows.get(k).round());
        }
        if (to - from + 1 > 10) {
            sb.append(",…");
        }
        return sb.toString();
    }

    static String roundsCsv(List<DetectContext.TraceRow> rows) {
        return roundsCsv(rows, 0, rows.size() - 1);
    }

    private static List<Integer> roundNums(List<DetectContext.TraceRow> rows, int from, int to) {
        List<Integer> nums = new ArrayList<>();
        for (int k = from; k <= to && nums.size() < 10; k++) {
            nums.add(rows.get(k).round());
        }
        return nums;
    }
}
