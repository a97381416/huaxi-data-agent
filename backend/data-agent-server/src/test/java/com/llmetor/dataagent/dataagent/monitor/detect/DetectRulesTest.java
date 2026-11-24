package com.llmetor.dataagent.dataagent.monitor.detect;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step3 规则引擎单测（V2 规格 §4/§9.1）：15 条规则正/反例，纯 Java 内存桩（不经 DB）。
 * 组织口径：一个问题（异常形态）= 一个测试场景。重点回归（05 文档 §9.1）：
 * REACT-002 恢复不命中、ANSWER-002 定性回答不命中、TOOL-004 字符串字面量不命中、
 * TOOL-001 结构化 success=true 不进关键词兜底。
 */
class DetectRulesTest {

    // ==================== 内存桩 ====================

    /** 内存 payload 访问器：traceId → tool_calls / 次轮观察 */
    static class MemPayloads implements DetectContext.PayloadAccessor {
        final Map<Long, List<DetectContext.ToolCallView>> calls = new HashMap<>();
        final Map<Long, List<DetectContext.ToolObservation>> observations = new HashMap<>();

        MemPayloads calls(long traceId, String name, String arguments) {
            calls.computeIfAbsent(traceId, k -> new ArrayList<>())
                    .add(new DetectContext.ToolCallView(name, arguments));
            return this;
        }

        /** 次轮 input 消息链：assistant(tool_calls) → tool×N（尾段=本轮观察） */
        MemPayloads observation(long nextTraceId, String toolName, String content) {
            observations.computeIfAbsent(nextTraceId, k -> new ArrayList<>())
                    .add(new DetectContext.ToolObservation(toolName, content));
            return this;
        }

        @Override
        public List<DetectContext.ToolCallView> toolCalls(long traceId) {
            return calls.getOrDefault(traceId, List.of());
        }

        @Override
        public List<DetectContext.ToolObservation> toolObservations(long traceId) {
            return observations.getOrDefault(traceId, List.of());
        }
    }

    static DetectContext.SessionRow session(String status, String answer, Integer rounds) {
        return new DetectContext.SessionRow("s1", "c1", "u1", "统计上季度项目数量", answer, status, rounds);
    }

    static DetectContext.SessionRow sessionAs(String userId, String question, String status,
                                              String answer, Integer rounds) {
        return new DetectContext.SessionRow("s1", "c1", userId, question, answer, status, rounds);
    }

    static DetectContext.TraceRow toolRound(long traceId, int round, List<String> tools) {
        return new DetectContext.TraceRow(traceId, round, "tool", tools, true, null, 1000L);
    }

    static DetectContext.TraceRow toolRoundSlow(long traceId, int round, List<String> tools, long ms) {
        return new DetectContext.TraceRow(traceId, round, "tool", tools, true, null, ms);
    }

    static DetectContext.TraceRow textRound(long traceId, int round) {
        return new DetectContext.TraceRow(traceId, round, "text", null, true, null, 500L);
    }

    static DetectContext.TraceRow failedRound(long traceId, int round, String error) {
        return new DetectContext.TraceRow(traceId, round, "tool", List.of("exec_sql"), false, error, 800L);
    }

    static DetectContext ctx(DetectContext.SessionRow s, List<DetectContext.TraceRow> rounds,
                             MemPayloads payloads) {
        return new DetectContext(s, rounds, payloads, DetectConfig.defaults());
    }

    /** 定制配置的上下文（perToolMs / expectedOutput / apiTools / docToolNames 等按规则注入） */
    static DetectContext ctxCfg(DetectContext.SessionRow s, List<DetectContext.TraceRow> rounds,
                                MemPayloads payloads, String ruleId, Map<String, Object> params) {
        DetectConfig cfg = DetectConfig.defaults();
        cfg.putRuleCfg(ruleId, true, params);
        return new DetectContext(s, rounds, payloads, cfg);
    }

    static DetectRule rule(String id) {
        return new DetectRuleRegistry().rules().stream()
                .filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    static List<Integer> roundsOf(DetectRule r, DetectContext ctx) {
        return r.eval(ctx).stream().map(RiskHit::round).collect(Collectors.toList());
    }

    // ==================== REACT-001 执行循环过多 ====================

    @Test
    void react001_hitsWhenDecisionRoundsExceed() {
        List<DetectContext.TraceRow> rounds = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rounds.add(textRound(i, i));
        }
        DetectContext c = ctx(session("completed", "答案", 12), rounds, new MemPayloads());
        List<RiskHit> hits = rule("REACT-001").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).severity()).isEqualTo("medium");
        assertThat(hits.get(0).round()).isEqualTo(12);
        // 证据增强：决策轮 X / 工具调用 Y 两个数
        assertThat(hits.get(0).detail()).contains("决策轮 12").contains("工具调用 0 次");
    }

    @Test
    void react001_silentAtExactThreshold() {
        List<DetectContext.TraceRow> rounds = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            rounds.add(textRound(i, i));
        }
        DetectContext c = ctx(session("completed", "答案", 10), rounds, new MemPayloads());
        assertThat(rule("REACT-001").eval(c)).isEmpty();
    }

    @Test
    void react001_callCountAloneDoesNotTrigger() {
        // 反例：8 决策轮（≤10）内多工具共 12 次调用 → 调用次数不单独触发
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            rounds.add(toolRound(i, i, List.of("executeSql", "listTables")));
            p.calls(i, "executeSql", "{\"sql\":\"SELECT " + i + "\"}");
            p.calls(i, "listTables", "{}");
        }
        DetectContext c = ctx(session("completed", "答案", 8), rounds, p);
        assertThat(rule("REACT-001").eval(c)).isEmpty();
    }

    @Test
    void react001_detailCountsToolCalls() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            rounds.add(toolRound(i, i, List.of("executeSql")));
            p.calls(i, "executeSql", "{\"sql\":\"SELECT 1\"}");
        }
        DetectContext c = ctx(session("completed", "答案", 11), rounds, p);
        List<RiskHit> hits = rule("REACT-001").eval(c);
        assertThat(hits.get(0).detail()).contains("决策轮 11").contains("工具调用 11 次");
    }

    // ==================== REACT-002 连续工具执行失败（恢复感知） ====================

    @Test
    void react002_hitsUnrecoveredFailStreak() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                textRound(1, 1),
                failedRound(2, 2, "SQLSyntaxError: Unknown column"),
                failedRound(3, 3, "timeout"),
                failedRound(4, 4, "reset")); // 到结束再无成功轮
        DetectContext c = ctx(session("error", null, 4), rounds, p);
        List<RiskHit> hits = rule("REACT-002").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).round()).isEqualTo(2);
        assertThat(hits.get(0).roundsHit()).isEqualTo("2,3,4");
        assertThat(hits.get(0).detail()).contains("未恢复");
    }

    @Test
    void react002_notHitsWhenRecoveredBySuccessRound() {
        // 反①（V2 修正，V1 会命中）：R2/R3 失败、R4 改参成功 → 恢复不命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                textRound(1, 1),
                failedRound(2, 2, "SQLSyntaxError"),
                failedRound(3, 3, "timeout"),
                toolRound(4, 4, List.of("exec_sql")),
                textRound(5, 5));
        p.calls(4, "exec_sql", "{\"sql\":\"SELECT count(*) FROM t WHERE x=2\"}");
        p.observation(5, "exec_sql", "{\"rows\":[{\"count\":86}]}");
        DetectContext c = ctx(session("completed", "共 86 个", 5), rounds, p);
        assertThat(rule("REACT-002").eval(c)).isEmpty();
    }

    @Test
    void react002_notHitsSingleFailureThenRecovery() {
        // 反②：单轮失败后即恢复 → 不命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                textRound(1, 1),
                failedRound(2, 2, "err"),
                toolRound(3, 3, List.of("exec_sql")),
                textRound(4, 4));
        p.calls(3, "exec_sql", "{\"sql\":\"SELECT 2\"}");
        p.observation(4, "exec_sql", "{\"rows\":[{\"c\":1}]}");
        DetectContext c = ctx(session("completed", "答案", 4), rounds, p);
        assertThat(rule("REACT-002").eval(c)).isEmpty();
    }

    @Test
    void react002_hitsOnlyLateUnrecoveredSegment() {
        // 中段失败但恢复（不判）+ 末段失败未恢复（判，定位末段首轮）
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                failedRound(1, 1, "e1"),
                failedRound(2, 2, "e2"),
                toolRound(3, 3, List.of("exec_sql")),
                failedRound(4, 4, "e3"),
                failedRound(5, 5, "e4"));
        p.calls(3, "exec_sql", "{\"sql\":\"SELECT 3\"}");
        p.observation(4, "exec_sql", "{\"rows\":[]}");
        DetectContext c = ctx(session("error", null, 5), rounds, p);
        List<RiskHit> hits = rule("REACT-002").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).round()).isEqualTo(4);
        assertThat(hits.get(0).roundsHit()).isEqualTo("4,5");
    }

    @Test
    void react002_failStreakConfigurable() {
        // failStreak=3：连续失败 2 轮不到阈值 → 不命中
        List<DetectContext.TraceRow> rounds = List.of(
                textRound(1, 1),
                failedRound(2, 2, "e"),
                failedRound(3, 3, "e"));
        DetectConfig cfg = DetectConfig.defaults();
        cfg.putRuleCfg("REACT-002", true, Map.of("failStreak", 3));
        DetectContext c = new DetectContext(session("error", null, 3), rounds, new MemPayloads(), cfg);
        assertThat(rule("REACT-002").eval(c)).isEmpty();
    }

    @Test
    void react002_allFailRoundsHit() {
        List<DetectContext.TraceRow> rounds = List.of(
                failedRound(1, 1, "e"),
                failedRound(2, 2, "e"));
        DetectContext c = ctx(session("error", null, 2), rounds, new MemPayloads());
        assertThat(roundsOf(rule("REACT-002"), c)).containsExactly(1);
    }

    // ==================== REACT-003 相同工具重复调用且无进展 ====================

    @Test
    void react003_hitsThreeIdenticalCallsSameResult() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                toolRound(2, 2, List.of("exec_sql")),
                toolRound(3, 3, List.of("exec_sql")));
        for (long tid = 1; tid <= 3; tid++) {
            p.calls(tid, "exec_sql", "{\"sql\":\"SELECT count(*) FROM doc_project\"}");
            p.observation((int) tid + 1, "exec_sql", "{\"rows\":[{\"c\":1}]}");
        }
        DetectContext c = ctx(session("completed", "共 100 个", 3), rounds, p);
        List<RiskHit> hits = rule("REACT-003").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).round()).isEqualTo(1);
        assertThat(hits.get(0).roundsHit()).isEqualTo("1,2,3");
        assertThat(hits.get(0).severity()).isEqualTo("medium"); // V2: high→medium
        assertThat(hits.get(0).detail()).contains("结果无变化");
    }

    @Test
    void react003_notHitsWhenArgsChanged() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                toolRound(2, 2, List.of("exec_sql")),
                toolRound(3, 3, List.of("exec_sql")));
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT count(*) FROM a\"}");
        p.calls(2, "exec_sql", "{\"sql\":\"SELECT count(*) FROM a WHERE x=1\"}");
        p.calls(3, "exec_sql", "{\"sql\":\"SELECT count(*) FROM a WHERE x=2\"}");
        DetectContext c = ctx(session("completed", "共 100 个", 3), rounds, p);
        assertThat(rule("REACT-003").eval(c)).isEmpty();
    }

    @Test
    void react003_keyOrderDoesNotMatter() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                toolRound(2, 2, List.of("exec_sql")),
                toolRound(3, 3, List.of("exec_sql")));
        // 键序不同、语义相同 → 归一化后仍相同（不算「改参」）
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT 1\",\"limit\":10}");
        p.calls(2, "exec_sql", "{\"limit\":10,\"sql\":\"SELECT 1\"}");
        p.calls(3, "exec_sql", "{\"sql\":\"SELECT 1\",\"limit\":10}");
        for (long tid = 1; tid <= 3; tid++) {
            p.observation((int) tid + 1, "exec_sql", "{\"rows\":[]}");
        }
        DetectContext c = ctx(session("completed", "1", 3), rounds, p);
        assertThat(rule("REACT-003").eval(c)).hasSize(1);
    }

    @Test
    void react003_notHitsWhenResultChanges() {
        // 反②（V2 修正）：同参但结果逐次不同（分页推进/数据变化）→ 不命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                toolRound(2, 2, List.of("exec_sql")),
                toolRound(3, 3, List.of("exec_sql")));
        for (long tid = 1; tid <= 3; tid++) {
            p.calls(tid, "exec_sql", "{\"sql\":\"SELECT * FROM t LIMIT 10 OFFSET 0\"}");
        }
        p.observation(2, "exec_sql", "{\"rows\":[{\"id\":1}]}");
        p.observation(3, "exec_sql", "{\"rows\":[{\"id\":1},{\"id\":2}]}");
        p.observation(4, "exec_sql", "{\"rows\":[{\"id\":1},{\"id\":2},{\"id\":3}]}");
        DetectContext c = ctx(session("completed", "共 3 条", 3), rounds, p);
        assertThat(rule("REACT-003").eval(c)).isEmpty();
    }

    @Test
    void react003_textRoundBreaksChain() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2),
                toolRound(3, 3, List.of("exec_sql")));
        for (long tid : new long[]{1, 3}) {
            p.calls(tid, "exec_sql", "{\"sql\":\"SELECT 1\"}");
        }
        DetectContext c = ctx(session("completed", "1", 3), rounds, p);
        assertThat(rule("REACT-003").eval(c)).isEmpty(); // 2 连不到 3（text 轮断链）
    }

    @Test
    void react003_twoRunsOfTwoNotAggregated() {
        // 同参 2 轮 → 中断 → 同参 2 轮：两段都不足 3，不出卡
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                toolRound(2, 2, List.of("exec_sql")),
                toolRound(3, 3, List.of("other_tool")),
                toolRound(4, 4, List.of("exec_sql")),
                toolRound(5, 5, List.of("exec_sql")));
        for (long tid : new long[]{1, 2, 4, 5}) {
            p.calls(tid, "exec_sql", "{\"sql\":\"SELECT 1\"}");
        }
        p.calls(3, "other_tool", "{}");
        DetectContext c = ctx(session("completed", "1", 5), rounds, p);
        assertThat(rule("REACT-003").eval(c)).isEmpty();
    }

    // ==================== REACT-004 最终回答缺失 ====================

    @Test
    void react004_hitsCompletedWithoutAnswer() {
        List<DetectContext.TraceRow> rounds = List.of(textRound(1, 1), textRound(2, 2));
        DetectContext c = ctx(session("completed", "  ", 2), rounds, new MemPayloads());
        List<RiskHit> hits = rule("REACT-004").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).detail()).contains("最终回答为空");
    }

    @Test
    void react004_hitsCompletedWithoutTextRound() {
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("exec_sql")));
        DetectContext c = ctx(session("completed", "答案", 1), rounds, new MemPayloads());
        assertThat(rule("REACT-004").eval(c)).hasSize(1);
    }

    @Test
    void react004_silentOnErrorStatus() {
        List<DetectContext.TraceRow> rounds = List.of(textRound(1, 1));
        DetectContext c = ctx(session("error", null, 1), rounds, new MemPayloads());
        assertThat(rule("REACT-004").eval(c)).isEmpty(); // error 状态走失败链路语义，不算该规则
    }

    // ==================== REACT-005 多轮执行无有效进展（V2 新增） ====================

    @Test
    void react005_hitsToolRotationSameResult() {
        // 正①：SQL→API→SQL 轮换、三次结果文本一致、参数各不同 → 命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                toolRound(2, 2, List.of("queryApi")),
                toolRound(3, 3, List.of("exec_sql")),
                textRound(4, 4));
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT a FROM t\"}");
        p.calls(2, "queryApi", "{\"url\":\"/api/a\"}");
        p.calls(3, "exec_sql", "{\"sql\":\"SELECT b FROM u\"}");
        for (int next = 2; next <= 4; next++) {
            p.observation(next, next == 3 ? "queryApi" : "exec_sql", "{\"rows\":[],\"msg\":\"无数据\"}");
        }
        DetectContext c = ctx(session("completed", "答案", 4), rounds, p);
        List<RiskHit> hits = rule("REACT-005").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).round()).isEqualTo(1);
        assertThat(hits.get(0).detail()).contains("结果完全相同");
    }

    @Test
    void react005_hitsEmptyResultsWithVaryingArgs() {
        // 正②：三轮空 rows、参数各不同 → 命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                toolRound(2, 2, List.of("exec_sql")),
                toolRound(3, 3, List.of("exec_sql")),
                textRound(4, 4));
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT 1 WHERE x=1\"}");
        p.calls(2, "exec_sql", "{\"sql\":\"SELECT 1 WHERE x=2\"}");
        p.calls(3, "exec_sql", "{\"sql\":\"SELECT 2 WHERE y=3\"}");
        p.observation(2, "exec_sql", "{\"rows\":[]}");
        p.observation(3, "exec_sql", "{\"rows\":[]}");
        p.observation(4, "exec_sql", "{\"rows\":[]}");
        DetectContext c = ctx(session("completed", "没有找到", 4), rounds, p);
        List<RiskHit> hits = rule("REACT-005").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).detail()).contains("无有效进展");
    }

    @Test
    void react005_hitsMixedEmptyRepresentations() {
        // 空结果形态在变（rows:[] / count:0）但都为空 → 条件②命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                toolRound(2, 2, List.of("exec_sql")),
                toolRound(3, 3, List.of("exec_sql")),
                textRound(4, 4));
        p.calls(1, "exec_sql", "{\"sql\":\"A\"}");
        p.calls(2, "exec_sql", "{\"sql\":\"B\"}");
        p.calls(3, "exec_sql", "{\"sql\":\"C\"}");
        p.observation(2, "exec_sql", "{\"rows\":[]}");
        p.observation(3, "exec_sql", "{\"count\": 0}");
        p.observation(4, "exec_sql", "{\"rows\":[],\"duration\":9}");
        DetectContext c = ctx(session("completed", "无", 4), rounds, p);
        assertThat(rule("REACT-005").eval(c)).hasSize(1);
    }

    @Test
    void react005_notHitsWhenLastRoundRecovers() {
        // 反：前两轮空第三轮有数据 → 不命中（窗口在恢复轮截断）
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                toolRound(2, 2, List.of("exec_sql")),
                toolRound(3, 3, List.of("exec_sql")),
                textRound(4, 4));
        p.calls(1, "exec_sql", "{\"sql\":\"A\"}");
        p.calls(2, "exec_sql", "{\"sql\":\"B\"}");
        p.calls(3, "exec_sql", "{\"sql\":\"C\"}");
        p.observation(2, "exec_sql", "{\"rows\":[]}");
        p.observation(3, "exec_sql", "{\"rows\":[]}");
        p.observation(4, "exec_sql", "{\"rows\":[{\"c\":5}]}");
        DetectContext c = ctx(session("completed", "共 5 个", 4), rounds, p);
        assertThat(rule("REACT-005").eval(c)).isEmpty();
    }

    @Test
    void react005_deduplicatedWithReact003() {
        // 反：三轮同参同结果 → REACT-003 管，本规则去重不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                toolRound(2, 2, List.of("exec_sql")),
                toolRound(3, 3, List.of("exec_sql")),
                textRound(4, 4));
        for (long tid = 1; tid <= 3; tid++) {
            p.calls(tid, "exec_sql", "{\"sql\":\"SELECT 1\"}");
            p.observation((int) tid + 1, "exec_sql", "{\"rows\":[]}");
        }
        DetectContext c = ctx(session("completed", "1", 4), rounds, p);
        assertThat(rule("REACT-005").eval(c)).isEmpty();
        assertThat(rule("REACT-003").eval(c)).hasSize(1);
    }

    @Test
    void react005_notHitsOnNonAdjacentToolRounds() {
        // text 轮打断：不足 3 个相邻工具轮 → 不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2),
                toolRound(3, 3, List.of("exec_sql")));
        p.calls(1, "exec_sql", "{\"sql\":\"A\"}");
        p.calls(3, "exec_sql", "{\"sql\":\"B\"}");
        DetectContext c = ctx(session("completed", "1", 3), rounds, p);
        assertThat(rule("REACT-005").eval(c)).isEmpty();
    }

    @Test
    void react005_notHitsTwoRoundsOnly() {
        // 窗口不足（2 轮空结果）→ 不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                toolRound(2, 2, List.of("exec_sql")));
        p.calls(1, "exec_sql", "{\"sql\":\"A\"}");
        p.calls(2, "exec_sql", "{\"sql\":\"B\"}");
        p.observation(2, "exec_sql", "{\"rows\":[]}");
        p.observation(3, "exec_sql", "{\"rows\":[]}");
        DetectContext c = ctx(session("completed", "无", 2), rounds, p);
        assertThat(rule("REACT-005").eval(c)).isEmpty();
    }

    // ==================== TOOL-001 工具调用失败（结构化状态优先 V2-D2） ====================

    @Test
    void tool001_hitsFailedRoundWithToolName() {
        List<DetectContext.TraceRow> rounds = List.of(
                failedRound(1, 5, "exec_sql failed: timeout after 30s"));
        DetectContext c = ctx(session("error", null, 5), rounds, new MemPayloads());
        List<RiskHit> hits = rule("TOOL-001").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).round()).isEqualTo(5);
        assertThat(hits.get(0).detail()).contains("exec_sql 调用失败");
    }

    @Test
    void tool001_hitsStructuredStatusError() {
        // 正①：{"status":"error","error":"Unknown column"} → 结构化命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2));
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT x\"}");
        p.observation(2, "exec_sql", "{\"status\":\"error\",\"error\":\"Unknown column\"}");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        List<RiskHit> hits = rule("TOOL-001").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).detail()).contains("status=error");
    }

    @Test
    void tool001_hitsStructuredSuccessFalse() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("queryApi")),
                textRound(2, 2));
        p.calls(1, "queryApi", "{}");
        p.observation(2, "queryApi", "{\"success\":false,\"message\":\"no permission\"}");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(roundsOf(rule("TOOL-001"), c)).containsExactly(1);
    }

    @Test
    void tool001_hitsStructuredErrorCodeNonZero() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("queryApi")),
                textRound(2, 2));
        p.calls(1, "queryApi", "{}");
        p.observation(2, "queryApi", "{\"error_code\":10001,\"message\":\"没有权限\"}");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(roundsOf(rule("TOOL-001"), c)).containsExactly(1);
    }

    @Test
    void tool001_hitsStructuredExceptionField() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2));
        p.calls(1, "exec_sql", "{}");
        p.observation(2, "exec_sql", "{\"exception\":\"java.sql.SQLException: timeout\"}");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(roundsOf(rule("TOOL-001"), c)).containsExactly(1);
    }

    @Test
    void tool001_hitsStructuredTimeoutTrue() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2));
        p.calls(1, "exec_sql", "{}");
        p.observation(2, "exec_sql", "{\"timeout\":true,\"partial\":\"...\"}");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(roundsOf(rule("TOOL-001"), c)).containsExactly(1);
    }

    @Test
    void tool001_structuredSuccessTrueSkipsKeywordFallback() {
        // 重点回归：结构化 success=true 时，正文含 "error handling" 字样不得进关键词兜底
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("docTool")),
                textRound(2, 2));
        p.calls(1, "docTool", "{}");
        p.observation(2, "docTool",
                "{\"success\":true,\"data\":{\"note\":\"see error handling guide\"}}");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(rule("TOOL-001").eval(c)).isEmpty();
    }

    @Test
    void tool001_structuredStatusOkSkipsKeywordFallback() {
        // {"status":"success"...} 即使正文含 error 词也不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2));
        p.calls(1, "exec_sql", "{}");
        p.observation(2, "exec_sql",
                "{\"status\":\"success\",\"rows\":[],\"hint\":\"无数据时 error 字段为空\"}");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(rule("TOOL-001").eval(c)).isEmpty();
    }

    @Test
    void tool001_hitsErrorBuriedAtChar3000() {
        // 正②（V2 修正，V1 漏）：错误信息出现在第 3000 字符处 → 全文扫描命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2));
        p.calls(1, "exec_sql", "{}");
        p.observation(2, "exec_sql", "结果集头部说明。".repeat(400) + "\n执行失败：duplicate key value");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(roundsOf(rule("TOOL-001"), c)).containsExactly(1);
    }

    @Test
    void tool001_docExemptionOnlyWhenConfigured() {
        // V2：docToolNames 默认空（取消豁免）→ tool_search 目录含失败词会命中；
        // 配置 docToolNames=[tool_search] 后豁免生效
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("tool_search")),
                textRound(2, 2));
        p.calls(1, "tool_search", "{}");
        p.observation(2, "tool_search", "已发现 5 个工具：描述「失败时返回 error 字段」");
        DetectContext defaultCtx = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(rule("TOOL-001").eval(defaultCtx)).hasSize(1); // 默认不豁免

        DetectConfig cfg = DetectConfig.defaults();
        cfg.putRuleCfg("TOOL-001", true, Map.of("docToolNames", List.of("tool_search")));
        DetectContext exempt = new DetectContext(session("completed", "答案", 2), rounds, p, cfg);
        assertThat(rule("TOOL-001").eval(exempt)).isEmpty(); // 配置后豁免
    }

    @Test
    void tool001_notHitsWhenLaterObservationsClean() {
        // R1 观察报错命中后，后续轮次观察干净不得连带命中（漏报不误报口径）
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                toolRound(2, 2, List.of("exec_sql")),
                toolRound(3, 3, List.of("exec_sql")),
                textRound(4, 4));
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT 1\"}");
        p.calls(2, "exec_sql", "{\"sql\":\"SELECT 2\"}");
        p.calls(3, "exec_sql", "{\"sql\":\"SELECT 3\"}");
        p.observation(2, "exec_sql", "{\"error\":\"syntax error\"}");          // R1 观察失败
        p.observation(3, "exec_sql", "{\"rows\":[{\"c\":1}],\"duration\":3}"); // 干净
        p.observation(4, "exec_sql", "{\"rows\":[{\"c\":2}],\"duration\":4}"); // 干净
        DetectContext c = ctx(session("completed", "共 2 个", 4), rounds, p);
        assertThat(roundsOf(rule("TOOL-001"), c)).containsExactly(1);
    }

    @Test
    void tool001_jsonNullErrorSilent() {
        // {"error": null} 是成功返回的常见形态，不得命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("executeSql")),
                textRound(2, 2));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT 1\"}");
        p.observation(2, "executeSql", "{\"rows\":[], \"error\": null, \"sql\":\"SELECT 1\"}");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(rule("TOOL-001").eval(c)).isEmpty();
    }

    @Test
    void tool001_errorCodeZeroSkipsFallback() {
        // error_code=0 是业务成功约定，键名里的 "error" 不得经关键词兜底误报
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("queryApi")),
                textRound(2, 2));
        p.calls(1, "queryApi", "{}");
        p.observation(2, "queryApi", "{\"error_code\":0,\"message\":\"ok\",\"data\":[1]}");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(rule("TOOL-001").eval(c)).isEmpty();
    }

    @Test
    void tool001_successPrefixMarkerSkipsKeyword() {
        // 校准回归（2026-11-20 真实会话）：executeSql 成功观察 "✅ 执行成功…" 返回数据里含 timeout/error
        // 词（告警内容/列名）——文本成功标记开头不进关键词兜底；失败观察（❌ 开头）照常命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("executeSql")),
                toolRound(2, 2, List.of("executeSql")),
                textRound(3, 3));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT id, timeout_status FROM t\"}");
        p.calls(2, "executeSql", "{\"sql\":\"SELECT 1\"}");
        p.observation(2, "executeSql",
                "✅ 执行成功（耗时 7ms，返回 9 行）\\n\\n执行的 SQL 含 timeout_status 列与 error 数据");
        p.observation(3, "executeSql", "❌ 执行失败（耗时 4ms）：ERROR: column not found");
        DetectContext c = ctx(session("completed", "答案", 3), rounds, p);
        assertThat(roundsOf(rule("TOOL-001"), c)).containsExactly(2);
    }

    @Test
    void tool001_successPrefixMarkerHandlesQuotedContent() {
        // 真实 trace 里观察 content 可能是 JSON 字符串化形态（首尾带引号），成功标记仍须豁免
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("executeSql")),
                textRound(2, 2));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT 1\"}");
        p.observation(2, "executeSql",
                "\"✅ 执行成功（耗时 7ms，返回 9 行）\\n数据含 timeout 状态与 error 字样\"");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(rule("TOOL-001").eval(c)).isEmpty();
    }

    @Test
    void tool001_docToolListConfigurable() {
        assertThat(ToolRules.ToolCallFailed.isDocTool("tool_search", List.of("tool_search", "listTables"))).isTrue();
        assertThat(ToolRules.ToolCallFailed.isDocTool("TOOL_SEARCH", List.of("tool_search"))).isTrue();
        assertThat(ToolRules.ToolCallFailed.isDocTool("executeSql", List.of("tool_search"))).isFalse();
        assertThat(ToolRules.ToolCallFailed.isDocTool(null, List.of("tool_search"))).isFalse();
    }

    @Test
    void tool001_structuredFailUnitCheck() {
        assertThat(ToolRules.ToolCallFailed.structuredFail("{\"status\":\"Failed\"}")).isNotNull();
        assertThat(ToolRules.ToolCallFailed.structuredFail("{\"error_code\":\"500\"}")).isNotNull();
        assertThat(ToolRules.ToolCallFailed.structuredFail("{\"error_code\":0}")).isNull();
        assertThat(ToolRules.ToolCallFailed.structuredFail("{\"error_code\":\"\"}")).isNull();
        assertThat(ToolRules.ToolCallFailed.structuredFail("{\"timeout\":\"true\"}")).isNotNull();
        assertThat(ToolRules.ToolCallFailed.structuredFail("[1,2]")).isNull();
        assertThat(ToolRules.ToolCallFailed.structuredFail("not json {")).isNull();
        assertThat(ToolRules.ToolCallFailed.structuredOk("{\"success\":true}")).isTrue();
        assertThat(ToolRules.ToolCallFailed.structuredOk("{\"status\":\"COMPLETED\"}")).isTrue();
        assertThat(ToolRules.ToolCallFailed.structuredOk("{\"rows\":[]}")).isFalse();
    }

    // ==================== TOOL-002 工具调用耗时异常（按工具阈值） ====================

    @Test
    void tool002_hitsPerToolThreshold() {
        // 正：executeSql 单工具轮 8s > perToolMs 5s → 命中
        List<DetectContext.TraceRow> rounds = List.of(
                toolRoundSlow(1, 1, List.of("executeSql"), 8000L),
                textRound(2, 2));
        DetectContext c = ctx(session("completed", "答案", 2), rounds, new MemPayloads());
        List<RiskHit> hits = rule("TOOL-002").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).round()).isEqualTo(1);
        assertThat(hits.get(0).severity()).isEqualTo("low");
        assertThat(hits.get(0).detail()).contains("executeSql");
    }

    @Test
    void tool002_unknownToolBelowDefaultSilent() {
        // 反①：未知工具轮 20s < defaultMs 30s → 不命中
        List<DetectContext.TraceRow> rounds = List.of(
                toolRoundSlow(1, 1, List.of("unknown_tool"), 20_000L),
                textRound(2, 2));
        DetectContext c = ctx(session("completed", "答案", 2), rounds, new MemPayloads());
        assertThat(rule("TOOL-002").eval(c)).isEmpty();
    }

    @Test
    void tool002_multiToolRoundUsesDefault() {
        // 反②：多工具轮 8s 含 executeSql → 无法归因，用 30s 判 → 不命中
        List<DetectContext.TraceRow> rounds = List.of(
                toolRoundSlow(1, 1, List.of("executeSql", "listTables"), 8000L),
                textRound(2, 2));
        DetectContext c = ctx(session("completed", "答案", 2), rounds, new MemPayloads());
        assertThat(rule("TOOL-002").eval(c)).isEmpty();
    }

    @Test
    void tool002_oldKeyMaxRoundMsCompatRead() {
        // 兼容读：配置行只有旧键 maxRoundMs=1000 → 2000ms 超阈值命中
        List<DetectContext.TraceRow> rounds = List.of(
                toolRoundSlow(1, 1, List.of("new_tool"), 2000L));
        DetectContext c = ctxCfg(session("completed", "答案", 1), rounds, new MemPayloads(),
                "TOOL-002", Map.of("maxRoundMs", 1000));
        assertThat(rule("TOOL-002").eval(c)).hasSize(1);
    }

    @Test
    void tool002_perToolSlowRoundCappedAtFive() {
        // 单问最多 5 条防刷屏
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            rounds.add(toolRoundSlow(i, i, List.of("executeSql"), 9000L));
            p.calls(i, "executeSql", "{\"sql\":\"SELECT " + i + "\"}");
        }
        DetectContext c = ctx(session("completed", "答案", 7), rounds, p);
        List<RiskHit> hits = rule("TOOL-002").eval(c);
        assertThat(hits).hasSize(5);
        assertThat(hits.get(4).detail()).contains("另有 2 轮超长略");
    }

    // ==================== TOOL-003 工具返回格式异常（按工具期望输出） ====================

    @Test
    void tool003_hitsStackObservation() {
        // 正②：任意工具返回堆栈 → 命中（TEXT_OR_JSON 缺省也判堆栈）
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("query_table")),
                textRound(2, 2));
        p.calls(1, "query_table", "{\"table\":\"a\"}");
        p.observation(2, "query_table", """
                java.lang.RuntimeException: connection reset
                	at com.foo.Bar.baz(Bar.java:42)
                	at com.foo.Qux.quux(Qux.java:7)
                """);
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        List<RiskHit> hits = rule("TOOL-003").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).detail()).contains("异常堆栈");
    }

    @Test
    void tool003_hitsBrokenJsonWhenExpectJson() {
        // 正①：executeSql 期望 JSON，返回截断 JSON → 命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("executeSql")),
                textRound(2, 2));
        p.calls(1, "executeSql", "{}");
        p.observation(2, "executeSql", "{\"rows\": [ {\"a\":1}, {\"a\":2}, {\"a\":");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        List<RiskHit> hits = rule("TOOL-003").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).detail()).contains("解析失败");
    }

    @Test
    void tool003_brokenJsonNotHitUnderTextOrJson() {
        // 期望 TEXT_OR_JSON 的工具：无堆栈特征的截断 JSON 不判（V2 只对期望 JSON 的工具强制解析）
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("query_table")),
                textRound(2, 2));
        p.calls(1, "query_table", "{}");
        p.observation(2, "query_table", "{\"rows\": [ {\"a\":1}, {\"a\":");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(rule("TOOL-003").eval(c)).isEmpty();
    }

    @Test
    void tool003_silentOnTextTable() {
        // Markdown/纯文本表格且无堆栈特征 → 不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("query_table")),
                textRound(2, 2));
        p.calls(1, "query_table", "{}");
        p.observation(2, "query_table", "名称 | 金额\nA | 100\nB | 200");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(rule("TOOL-003").eval(c)).isEmpty();
    }

    @Test
    void tool003_silentOnValidJsonBusinessFail() {
        // 合法 JSON（业务码失败）归 TOOL-007 管，TOOL-003 不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("executeSql")),
                textRound(2, 2));
        p.calls(1, "executeSql", "{}");
        p.observation(2, "executeSql", "{\"code\":500,\"message\":\"internal\"}");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(rule("TOOL-003").eval(c)).isEmpty();
    }

    @Test
    void tool003_severityIsMedium() {
        assertThat(rule("TOOL-003").severity()).isEqualTo("medium"); // V2: low→medium
    }

    @Test
    void tool003_exceptionStartLineFeature() {
        // Exception: 起始行特征（无 at 堆栈行也命中）
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("query_table")),
                textRound(2, 2));
        p.calls(1, "query_table", "{}");
        p.observation(2, "query_table", "java.sql.SQLException: ORA-00942 不存在");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(rule("TOOL-003").eval(c)).hasSize(1);
    }

    // ==================== TOOL-004 SQL 非法操作（词法分析 V2-D7） ====================

    @Test
    void tool004_hitsIllegalWrite() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 2, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"sql\":\"DROP TABLE doc_project\"}");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        List<RiskHit> hits = rule("TOOL-004").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).detail()).contains("DROP");
        assertThat(hits.get(0).severity()).isEqualTo("high");
    }

    @Test
    void tool004_hitsWriteAfterBlockComment() {
        // 正②：/* x */ DROP TABLE t → 剥注释后首关键字 DROP
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"sql\":\"/* 说明注释 */ DROP TABLE t\"}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        assertThat(roundsOf(rule("TOOL-004"), c)).hasSize(1);
    }

    @Test
    void tool004_stringLiteralDeleteSilent() {
        // 反①（重点回归，V1 误报）：WHERE remark='DELETE' 字符串字面量里的写词不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql",
                "{\"sql\":\"SELECT * FROM project WHERE remark='DELETE' AND status='UPDATE pending'\"}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        assertThat(rule("TOOL-004").eval(c)).isEmpty();
    }

    @Test
    void tool004_cteSelectSilent() {
        // 反②：WITH cte AS (SELECT …) SELECT … → 跳 CTE 后主语句 SELECT
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"sql\":\"WITH cte AS (SELECT id FROM t WHERE x=1) SELECT * FROM cte\"}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        assertThat(rule("TOOL-004").eval(c)).isEmpty();
    }

    @Test
    void tool004_multiCteUpdateHits() {
        // 多 CTE 跳完定位主语句 UPDATE → 命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"sql\":\"WITH a AS (SELECT 1), b AS (SELECT (SELECT 2)) UPDATE t SET x=1\"}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        assertThat(rule("TOOL-004").eval(c)).hasSize(1);
    }

    @Test
    void tool004_lineCommentedDeleteHits() {
        // 行注释后的 DELETE 是真语句（注释只剥到行尾）
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"sql\":\"-- 查询注释\\nDELETE FROM t WHERE id=1\"}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        assertThat(rule("TOOL-004").eval(c)).hasSize(1);
    }

    @Test
    void tool004_notHitsColumnNames() {
        // update_time / deleted_at 是列名，不参与判型
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT update_time, deleted_at FROM doc_project LIMIT 1\"}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        assertThat(rule("TOOL-004").eval(c)).isEmpty();
    }

    @Test
    void tool004_whitelistUserExempt() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"sql\":\"DELETE FROM t\"}");
        // 新键 writeWhitelist 白名单豁免；普通用户照常命中
        DetectConfig cfg = DetectConfig.defaults();
        cfg.putRuleCfg("TOOL-004", true, Map.of("writeWhitelist", List.of("etl-admin")));
        DetectContext exempt = new DetectContext(
                new DetectContext.SessionRow("s1", "c1", "etl-admin", "清理", "done", "completed", 1),
                rounds, p, cfg);
        assertThat(rule("TOOL-004").eval(exempt)).isEmpty();

        // 旧键 writeAllowedUsers 兼容读
        DetectConfig legacy = DetectConfig.defaults();
        legacy.putRuleCfg("TOOL-004", true, Map.of("writeAllowedUsers", List.of("etl-admin")));
        DetectContext exemptLegacy = new DetectContext(
                new DetectContext.SessionRow("s1", "c1", "etl-admin", "清理", "done", "completed", 1),
                rounds, p, legacy);
        assertThat(rule("TOOL-004").eval(exemptLegacy)).isEmpty();

        DetectContext normal = ctx(session("completed", "done", 1), rounds, p);
        assertThat(rule("TOOL-004").eval(normal)).hasSize(1);
    }

    // ==================== TOOL-005 统计查询空结果风险提示 ====================

    @Test
    void tool005_defaultEnabledTrue() {
        DetectConfig cfg = DetectConfig.defaults();
        DetectRule r = rule("TOOL-005");
        assertThat(r.defaultEnabled()).isTrue(); // V2：逻辑闭合后默认启用
        assertThat(r.enabled(cfg)).isTrue();
    }

    @Test
    void tool005_hitsEmptyStatWithDeterministicAnswer() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("executeSql")),
                textRound(2, 2));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT count(*) FROM doc_project\"}");
        p.observation(2, "executeSql", "{\"rows\": []}");
        DetectContext c = ctx(session("completed", "统计上季度项目数量，共 86 个项目完成验收", 2), rounds, p);
        List<RiskHit> hits = rule("TOOL-005").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).detail()).contains("确定性统计结论");
    }

    @Test
    void tool005_silentWhenAnswerDeclaresNoData() {
        // 反①：回答「暂无符合条件的数据」→ 不命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("executeSql")),
                textRound(2, 2));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT count(*) FROM doc_project\"}");
        p.observation(2, "executeSql", "{\"rows\": []}");
        DetectContext c = ctx(session("completed", "数据库中暂无符合条件的数据", 2), rounds, p);
        assertThat(rule("TOOL-005").eval(c)).isEmpty();
    }

    @Test
    void tool005_silentOnNonStatQuestion() {
        // 反②：非统计类问题不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("executeSql")),
                textRound(2, 2));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT * FROM doc_project\"}");
        p.observation(2, "executeSql", "{\"rows\": []}");
        DetectContext c = ctx(sessionAs("u1", "项目列表", "completed", "没有找到", 2), rounds, p);
        assertThat(rule("TOOL-005").eval(c)).isEmpty();
    }

    @Test
    void tool005_silentWhenRowsNonEmpty() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("executeSql")),
                textRound(2, 2));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT count(*) FROM doc_project\"}");
        p.observation(2, "executeSql", "{\"rows\": [{\"count\": 5}]}");
        DetectContext c = ctx(session("completed", "统计上季度项目数量，共 86 个项目", 2), rounds, p);
        assertThat(rule("TOOL-005").eval(c)).isEmpty(); // 有结果不判
    }

    @Test
    void tool005_skipsTool001HitRound() {
        // 条件②：TOOL-001 命中轮不算「成功 SQL 轮」→ 该轮观察带失败词不作为空结果来源
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("executeSql")),
                textRound(2, 2));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT count(*) FROM doc_project\"}");
        p.observation(2, "executeSql", "{\"rows\": []}");
        DetectContext c = ctx(session("completed", "统计项目数量，共 86 个", 2), rounds, p);
        c.addHits("TOOL-001", List.of(new RiskHit("TOOL-001", "high", 1, 1L, null, "x", "{}")));
        assertThat(rule("TOOL-005").eval(c)).isEmpty();
    }

    // ==================== TOOL-006 工具调用参数异常（V2 新增） ====================

    @Test
    void tool006_hitsNullSqlArg() {
        // 正①：{"sql":null} → 命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"sql\":null}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        List<RiskHit> hits = rule("TOOL-006").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).detail()).contains("sql").contains("空值");
    }

    @Test
    void tool006_hitsMissingRequiredArg() {
        // executeSql 必填 sql 缺失 → 命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"limit\":10}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        assertThat(roundsOf(rule("TOOL-006"), c)).hasSize(1);
    }

    @Test
    void tool006_hitsTruncatedSql() {
        // 正②：{"sql":"SELECT * FROM"} → 残缺命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT * FROM\"}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        List<RiskHit> hits = rule("TOOL-006").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).detail()).contains("残缺");
    }

    @Test
    void tool006_silentValidSql() {
        // 反：{"sql":"SELECT 1"} → 不命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT 1\"}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        assertThat(rule("TOOL-006").eval(c)).isEmpty();
    }

    @Test
    void tool006_hitsMissingSkillName() {
        // Skill 必填 name 缺失
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("Skill")));
        p.calls(1, "Skill", "{}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        List<RiskHit> hits = rule("TOOL-006").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).detail()).contains("name");
    }

    @Test
    void tool006_unknownToolExplicitNullArg() {
        // 未知工具通用检查：显式 null 参数 → 命中；正常参数不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("mystery")),
                toolRound(2, 2, List.of("mystery")));
        p.calls(1, "mystery", "{\"path\":null}");
        p.calls(2, "mystery", "{\"path\":\"/a\",\"limit\":5}");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(roundsOf(rule("TOOL-006"), c)).containsExactly(1);
    }

    @Test
    void tool006_hitsSqlEndingWithComma() {
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT name, age,\"}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        assertThat(rule("TOOL-006").eval(c)).hasSize(1);
    }

    @Test
    void tool006_hitsSelectMissingFrom() {
        // 缺 FROM 的 SELECT 且列清单以裸标识符收尾
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT project_name, owner_name\"}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        assertThat(rule("TOOL-006").eval(c)).hasSize(1);
    }

    @Test
    void tool006_optionalArgMissingSilent() {
        // 可选参数缺失（requiredArgs 未含的键）不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(toolRound(1, 1, List.of("executeSql")));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT count(*) FROM t\"}");
        DetectContext c = ctx(session("completed", "答案", 1), rounds, p);
        assertThat(rule("TOOL-006").eval(c)).isEmpty();
    }

    // ==================== TOOL-007 API 业务执行失败（V2 新增） ====================

    @Test
    void tool007_silentWhenNoApiToolsConfigured() {
        // 默认 apiTools={} → 规则不触发（等业务码约定配置）
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("queryApi")),
                textRound(2, 2));
        p.calls(1, "queryApi", "{}");
        p.observation(2, "queryApi", "{\"code\":10001,\"message\":\"没有权限\"}");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(rule("TOOL-007").eval(c)).isEmpty();
    }

    @Test
    void tool007_hitsBusinessCodeFail() {
        // 正：code=10001 ∉ [0,200] → 命中，detail 带 message
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("queryProjectApi")),
                textRound(2, 2));
        p.calls(1, "queryProjectApi", "{}");
        p.observation(2, "queryProjectApi", "{\"code\":10001,\"message\":\"没有权限\",\"data\":null}");
        DetectContext c = ctxCfg(session("completed", "答案", 2), rounds, p,
                "TOOL-007", Map.of("apiTools", Map.of("queryProjectApi",
                        Map.of("codeField", "code", "successCodes", List.of(0, 200), "messageField", "message"))));
        List<RiskHit> hits = rule("TOOL-007").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).detail()).contains("10001").contains("没有权限");
    }

    @Test
    void tool007_silentOnSuccessCode() {
        // 反：{"code":0,"data":[…]} → 不命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("queryProjectApi")),
                textRound(2, 2));
        p.calls(1, "queryProjectApi", "{}");
        p.observation(2, "queryProjectApi", "{\"code\":0,\"data\":[{\"id\":1}]}");
        DetectContext c = ctxCfg(session("completed", "答案", 2), rounds, p,
                "TOOL-007", Map.of("apiTools", Map.of("queryProjectApi",
                        Map.of("codeField", "code", "successCodes", List.of(0, 200)))));
        assertThat(rule("TOOL-007").eval(c)).isEmpty();
    }

    @Test
    void tool007_nonConfiguredToolSilent() {
        // 非 apiTools 配置的工具不检查
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("otherApi")),
                textRound(2, 2));
        p.calls(1, "otherApi", "{}");
        p.observation(2, "otherApi", "{\"code\":500,\"message\":\"boom\"}");
        DetectContext c = ctxCfg(session("completed", "答案", 2), rounds, p,
                "TOOL-007", Map.of("apiTools", Map.of("queryProjectApi",
                        Map.of("codeField", "code", "successCodes", List.of(0)))));
        assertThat(rule("TOOL-007").eval(c)).isEmpty();
    }

    @Test
    void tool007_stringSuccessCode() {
        // 业务码为字符串约定：R1 返回 SUCCESS（不判），R2 返回 ERR_AUTH → 命中定位 R2
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("queryApi")),
                toolRound(2, 2, List.of("queryApi")),
                textRound(3, 3));
        p.calls(1, "queryApi", "{}");
        p.calls(2, "queryApi", "{}");
        p.observation(2, "queryApi", "{\"code\":\"SUCCESS\",\"data\":{}}");
        p.observation(3, "queryApi", "{\"code\":\"ERR_AUTH\",\"message\":\"denied\"}");
        DetectContext c = ctxCfg(session("completed", "答案", 3), rounds, p,
                "TOOL-007", Map.of("apiTools", Map.of("queryApi",
                        Map.of("codeField", "code", "successCodes", List.of("SUCCESS"), "messageField", "message"))));
        assertThat(roundsOf(rule("TOOL-007"), c)).containsExactly(2);
    }

    @Test
    void tool007_nonJsonObservationSkipped() {
        // Observation 非 JSON → 交给 TOOL-003，本规则不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("queryApi")),
                textRound(2, 2));
        p.calls(1, "queryApi", "{}");
        p.observation(2, "queryApi", "服务暂时不可用，请稍后重试");
        DetectContext c = ctxCfg(session("completed", "答案", 2), rounds, p,
                "TOOL-007", Map.of("apiTools", Map.of("queryApi",
                        Map.of("codeField", "code", "successCodes", List.of(0)))));
        assertThat(rule("TOOL-007").eval(c)).isEmpty();
    }

    // ==================== SKILL-001 Skill 加载失败（V2 新增） ====================

    @Test
    void skill001_hitsSkillNotFound() {
        // 正：Skill(name="data-analys") 拼错 → 返回未找到 → 命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("Skill")),
                textRound(2, 2));
        p.calls(1, "Skill", "{\"name\":\"data-analys\"}");
        p.observation(2, "Skill", "未找到技能: data-analys。当前可用技能: data-analysis, chart-maker");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        List<RiskHit> hits = rule("SKILL-001").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).severity()).isEqualTo("medium");
        assertThat(hits.get(0).detail()).contains("data-analys");
        assertThat(hits.get(0).detail()).contains("未找到");
    }

    @Test
    void skill001_silentOnNormalLoad() {
        // 反：正常加载返回「技能工作目录: …」+ SKILL.md 正文 → 不命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("Skill")),
                textRound(2, 2));
        p.calls(1, "Skill", "{\"name\":\"data-analysis\"}");
        p.observation(2, "Skill", "技能工作目录: /skills/data-analysis\n\n# data-analysis\n按以下流程分析…");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(rule("SKILL-001").eval(c)).isEmpty();
    }

    @Test
    void skill001_onlyDetectsSkillToolObservation() {
        // 其他工具观察含「可用技能」字样不判（按工具名匹配）
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("executeSql")),
                textRound(2, 2));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT 1\"}");
        p.observation(2, "executeSql", "系统提示：可用技能见帮助");
        DetectContext c = ctx(session("completed", "答案", 2), rounds, p);
        assertThat(rule("SKILL-001").eval(c)).isEmpty();
    }

    @Test
    void skill001_customToolNameConfigurable() {
        // skillToolName 可配（自定义技能加载工具名）
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("load_skill")),
                textRound(2, 2));
        p.calls(1, "load_skill", "{\"name\":\"x\"}");
        p.observation(2, "load_skill", "未找到技能: x。当前可用技能: y");
        DetectContext c = ctxCfg(session("completed", "答案", 2), rounds, p,
                "SKILL-001", Map.of("skillToolName", "load_skill"));
        assertThat(rule("SKILL-001").eval(c)).hasSize(1);
    }

    // ==================== ANSWER-001 失败后仍给出确定性结论（恢复感知 V2-D4） ====================

    @Test
    void answer001_needsPriorToolFailureEvidence() {
        // 依赖 TOOL-001/REACT-002 命中或 success=false 轮；两者皆无时不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2));
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT 1\"}");
        p.observation(2, "exec_sql", "{\"error\":\"timeout\"}");
        DetectContext c = ctx(session("completed", "上季度共有 86 个项目付款", 2), rounds, p);
        assertThat(rule("ANSWER-001").eval(c)).isEmpty();

        c.addHits("TOOL-001", List.of(new RiskHit("TOOL-001", "high", 1, 1L, null, "x", "{}")));
        List<RiskHit> hits = rule("ANSWER-001").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).detail()).contains("确定性结论");
    }

    @Test
    void answer001_independentScanOfFailedTraceRounds() {
        // 独立扫 success=false 轮（无需 TOOL-001 先命中，口径与 V1 一致）
        List<DetectContext.TraceRow> rounds = List.of(
                failedRound(1, 1, "exec_sql failed: connection reset"),
                textRound(2, 2));
        DetectContext c = ctx(session("completed", "上季度总计 86 个项目", 2), rounds, new MemPayloads());
        assertThat(rule("ANSWER-001").eval(c)).hasSize(1);
    }

    @Test
    void answer001_notHitsWhenRecoveredWithSuccessDataRound() {
        // 反①（V2 修正，V1 误报）：R2 失败、R3 改参成功取到 86、答 86 → 不命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                textRound(1, 1),
                failedRound(2, 2, "exec_sql failed: syntax error"),
                toolRound(3, 3, List.of("exec_sql")),
                textRound(4, 4));
        p.calls(3, "exec_sql", "{\"sql\":\"SELECT count(*) FROM t WHERE corrected\"}");
        p.observation(4, "exec_sql", "{\"rows\":[{\"count\":86}]}");
        DetectContext c = ctx(session("completed", "今年共完成 86 个", 4), rounds, p);
        assertThat(rule("ANSWER-001").eval(c)).isEmpty();
    }

    @Test
    void answer001_recoveryWithEmptyResultDoesNotCount() {
        // 恢复轮结果为空不算数据来源轮 → 仍命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                failedRound(1, 1, "exec_sql failed: syntax error"),
                toolRound(2, 2, List.of("exec_sql")),
                textRound(3, 3));
        p.calls(2, "exec_sql", "{\"sql\":\"SELECT count(*) FROM t\"}");
        p.observation(3, "exec_sql", "{\"rows\":[]}");
        DetectContext c = ctx(session("completed", "上季度共有 86 个项目", 3), rounds, p);
        assertThat(rule("ANSWER-001").eval(c)).hasSize(1);
    }

    @Test
    void answer001_notHitsWhenAnswerDeclaresFailure() {
        // 反②：答「抱歉，暂时无法查询」→ 不命中
        DetectContext c = ctx(session("completed", "抱歉，暂时无法查询到相关数据", 2), List.of(), new MemPayloads());
        c.addHits("TOOL-001", List.of(new RiskHit("TOOL-001", "high", 1, 1L, null, "x", "{}")));
        assertThat(rule("ANSWER-001").eval(c)).isEmpty();
    }

    @Test
    void answer001_notHitsQualitativeAnswer() {
        // 定性回答（无确定性数字）→ 不命中
        DetectContext c = ctx(session("completed", "整体完成情况良好", 2), List.of(), new MemPayloads());
        c.addHits("REACT-002", List.of(new RiskHit("REACT-002", "high", 2, 2L, "2,3", "x", "{}")));
        assertThat(rule("ANSWER-001").eval(c)).isEmpty();
    }

    // ==================== ANSWER-002 工具结果与最终答案数值不一致（真数值比对 V2-D3） ====================

    @Test
    void answer002_hitsMismatchedSingleStat() {
        // 正①：count=86、答「共有 42 个」→ 命中（detail 给 T 与冲突数）
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2));
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT count(*) FROM t\"}");
        p.observation(2, "exec_sql", "{\"count\": 86}");
        DetectContext c = ctx(session("completed", "上季度共有 42 个项目付款", 2), rounds, p);
        List<RiskHit> hits = rule("ANSWER-002").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).detail()).contains("86").contains("42");
        assertThat(hits.get(0).severity()).isEqualTo("medium");
    }

    @Test
    void answer002_silentWhenNumberPresent() {
        // 反①：count=86、答「共 86 个」→ 不命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2));
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT count(*) FROM t\"}");
        p.observation(2, "exec_sql", "{\"count\": 86}");
        DetectContext c = ctx(session("completed", "共 86 个项目，涉及金额 1200 万元", 2), rounds, p);
        assertThat(rule("ANSWER-002").eval(c)).isEmpty();
    }

    @Test
    void answer002_silentOnQualitativeAnswer() {
        // 反②（V1 误报主场景修正）：count=86、答「整体完成情况良好」→ 不命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2));
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT count(*) FROM t\"}");
        p.observation(2, "exec_sql", "{\"count\": 86}");
        DetectContext c = ctx(session("completed", "整体完成情况良好", 2), rounds, p);
        assertThat(rule("ANSWER-002").eval(c)).isEmpty();
    }

    @Test
    void answer002_silentOnApproxNumber() {
        // 反③：约数（约 80 多个）不构成确定性冲突，首版保守不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2));
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT count(*) FROM t\"}");
        p.observation(2, "exec_sql", "{\"count\": 86}");
        DetectContext c = ctx(session("completed", "今年完成了约 80 多个项目", 2), rounds, p);
        assertThat(rule("ANSWER-002").eval(c)).isEmpty();
    }

    @Test
    void answer002_skipsMultiFieldResult() {
        // 多数字字段/多行聚合 → 本版跳过不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2));
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT * FROM t\"}");
        p.observation(2, "exec_sql",
                "{\"total\": 86, \"rows\": [{\"amount\": 10}, {\"amount\": 20}], \"duration\": 5}");
        DetectContext c = ctx(session("completed", "共 42 个项目", 2), rounds, p);
        assertThat(rule("ANSWER-002").eval(c)).isEmpty();
    }

    @Test
    void answer002_thousandSeparatorNormalized() {
        // 千分位归一：T=1234，答「共 1,234 个」→ 一致不判
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                textRound(2, 2));
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT count(*) FROM t\"}");
        p.observation(2, "exec_sql", "{\"count\": 1234}");
        DetectContext c = ctx(session("completed", "共 1,234 条记录", 2), rounds, p);
        assertThat(rule("ANSWER-002").eval(c)).isEmpty();
    }

    @Test
    void answer002_amountConclusionPatternHits() {
        // 金额型结论「为 42 亿元」与 total=86 冲突 → 命中
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("executeSql")),
                textRound(2, 2));
        p.calls(1, "executeSql", "{\"sql\":\"SELECT sum(amount) AS total FROM t\"}");
        p.observation(2, "executeSql", "{\"total\": 86, \"duration\": 3}");
        DetectContext c = ctx(session("completed", "系统总投资完成额为 42 亿元。", 2), rounds, p);
        assertThat(rule("ANSWER-002").eval(c)).hasSize(1);
    }

    @Test
    void answer002_lastStatResultWins() {
        // 两次统计：取最后一次成功的统计值（86）判定
        MemPayloads p = new MemPayloads();
        List<DetectContext.TraceRow> rounds = List.of(
                toolRound(1, 1, List.of("exec_sql")),
                toolRound(2, 2, List.of("exec_sql")),
                textRound(3, 3));
        p.calls(1, "exec_sql", "{\"sql\":\"SELECT count(*) FROM a\"}");
        p.calls(2, "exec_sql", "{\"sql\":\"SELECT count(*) FROM b\"}");
        p.observation(2, "exec_sql", "{\"count\": 5}");
        p.observation(3, "exec_sql", "{\"count\": 86}");
        DetectContext c = ctx(session("completed", "最终共有 42 个项目", 3), rounds, p);
        List<RiskHit> hits = rule("ANSWER-002").eval(c);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).round()).isEqualTo(2); // 定位最后一次统计轮
        assertThat(hits.get(0).detail()).contains("86");
    }

    @Test
    void answer002_renameTitle() {
        assertThat(rule("ANSWER-002").title()).isEqualTo("工具结果与最终答案数值不一致");
    }

    // ==================== 全链路：规则注册表顺序与版本 ====================

    @Test
    void registry_containsAllFifteenRulesInOrder() {
        List<String> ids = new DetectRuleRegistry().rules().stream()
                .map(DetectRule::id).collect(Collectors.toList());
        assertThat(ids).containsExactly(
                "REACT-001", "REACT-002", "REACT-003", "REACT-004", "REACT-005",
                "TOOL-001", "TOOL-002", "TOOL-003", "TOOL-004", "TOOL-005", "TOOL-006", "TOOL-007",
                "SKILL-001",
                "ANSWER-001", "ANSWER-002");
    }

    @Test
    void registry_versionIsV2() {
        assertThat(DetectRuleRegistry.VERSION).isEqualTo("v2");
    }

    @Test
    void registry_newRulesDefaults() {
        DetectRuleRegistry registry = new DetectRuleRegistry();
        assertThat(registry.byId("TOOL-006").defaultParams()).containsKey("requiredArgs");
        assertThat(registry.byId("TOOL-007").defaultParams())
                .containsEntry("apiTools", Map.of());
        assertThat(registry.byId("REACT-005").defaultParams())
                .containsEntry("noProgressRounds", 3);
        assertThat(registry.byId("SKILL-001").defaultParams()).containsKey("skillToolName");
        // 阈值/等级调整项
        assertThat(registry.byId("REACT-001").defaultParams()).containsEntry("maxRounds", 10);
        assertThat(registry.byId("TOOL-001").defaultParams()).containsEntry("obsTruncateChars", 8000);
        assertThat(registry.byId("TOOL-001").defaultParams()).containsEntry("docToolNames", List.of());
        assertThat(registry.byId("TOOL-002").title()).isEqualTo("工具调用耗时异常");
        assertThat(registry.byId("TOOL-005").title()).isEqualTo("统计查询空结果风险提示");
    }

    // ==================== 规则支持：SQL 词法与指纹（V2 共用件直测） ====================

    @Test
    void ruleSupport_sqlLexerEdgeCases() {
        assertThat(RuleSupport.illegalWriteKeyword("select 1")).isNull();
        assertThat(RuleSupport.illegalWriteKeyword("")).isNull();
        assertThat(RuleSupport.illegalWriteKeyword("-- only comment")).isNull();
        assertThat(RuleSupport.illegalWriteKeyword("TRUNCATE TABLE t")).isEqualTo("TRUNCATE");
        assertThat(RuleSupport.illegalWriteKeyword("GRANT ALL ON t TO u")).isEqualTo("GRANT");
        assertThat(RuleSupport.illegalWriteKeyword("WITH x AS (SELECT 1) EXPLAIN SELECT * FROM x")).isNull();
        assertThat(RuleSupport.illegalWriteKeyword("explain select * from t")).isNull();
        assertThat(RuleSupport.illegalWriteKeyword("/* c */ UPDATE t SET a=1")).isEqualTo("UPDATE");
        // 字符串里的括号/注释不干扰词法
        assertThat(RuleSupport.illegalWriteKeyword(
                "SELECT * FROM t WHERE note='/* fake */ (delete)'")).isNull();

        assertThat(RuleSupport.isTruncatedSql("SELECT 1")).isFalse();
        assertThat(RuleSupport.isTruncatedSql("SELECT count(*) FROM t")).isFalse();
        assertThat(RuleSupport.isTruncatedSql("SELECT a FROM t WHERE x = 'end '")).isFalse();
        assertThat(RuleSupport.isTruncatedSql("SELECT name WHERE")).isTrue();
        assertThat(RuleSupport.isTruncatedSql("  ")).isFalse();
    }

    @Test
    void ruleSupport_resultFingerprintAndEmptyResult() {
        List<DetectContext.ToolObservation> a = List.of(
                new DetectContext.ToolObservation("t", "{\"rows\": []}"));
        List<DetectContext.ToolObservation> b = List.of(
                new DetectContext.ToolObservation("t", "{\"rows\":[]}"));
        assertThat(RuleSupport.resultFingerprint(a)).isEqualTo(RuleSupport.resultFingerprint(b)); // 去空白
        assertThat(RuleSupport.isEmptyResult(a)).isTrue();
        assertThat(RuleSupport.isEmptyResult(List.of(
                new DetectContext.ToolObservation("t", "{\"rows\":[{\"c\":1}]}")))).isFalse();
        assertThat(RuleSupport.isEmptyResult(List.of())).isTrue(); // 无观察=空文本特征
    }
}
