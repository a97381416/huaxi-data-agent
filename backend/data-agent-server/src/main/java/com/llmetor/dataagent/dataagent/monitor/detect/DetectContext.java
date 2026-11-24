package com.llmetor.dataagent.dataagent.monitor.detect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检测上下文（03 方案 §4 ②）：一次提问的检测数据视图——
 * 瘦行（session + trace 轮次）一次性读入，payload 大字段<b>惰性</b>按轮取
 * （生产 = SQL 侧 jsonb 截取前 2000 字符，不整拉 input 大字段；单测 = 内存桩）。
 *
 * <p>观察口径（ReAct「本轮决策 + 次轮观察」）：第 i 轮的工具调用观察 = 第 i+1 轮
 * input 中「最后一个带 tool_calls 的 assistant 消息之后」的 role=tool 消息——
 * 只看尾段，规避跨轮旧观察重复命中（同工具反复调用时 input 里累积了历史 tool 消息）。
 */
public class DetectContext {

    /** 提问行关键字段（agentx_monitor_session） */
    public record SessionRow(String sessionId, String conversationId, String userId,
                             String question, String answer, String status, Integer rounds) {
    }

    /** 轮次瘦行（agentx_monitor_trace，按 round 升序） */
    public record TraceRow(long traceId, int round, String roundType, List<String> toolNames,
                           boolean success, String errorMessage, Long durationMs) {
    }

    /** 工具调用视图（payload.output_data 的 tool_calls 元素；arguments 为原始 JSON 字符串） */
    public record ToolCallView(String name, String arguments) {
    }

    /** 工具观察视图（次轮 input 的 role=tool 消息，content 已 SQL 侧截 2000） */
    public record ToolObservation(String name, String content) {
    }

    /** payload 访问器（生产 = JDBC 惰性查询；单测 = 内存桩），实现须自吞异常返回空列表 */
    public interface PayloadAccessor {
        List<ToolCallView> toolCalls(long traceId);

        List<ToolObservation> toolObservations(long traceId);
    }

    private final SessionRow session;
    private final List<TraceRow> rounds; // 按 round 升序
    private final PayloadAccessor payloads;
    private final DetectConfig config;   // 本次检测的合并配置（规则 eval 读阈值用）
    private final Map<Integer, TraceRow> byRound = new HashMap<>();
    private final Map<Integer, List<ToolCallView>> callCache = new HashMap<>();
    private final Map<Integer, List<ToolObservation>> obsCache = new HashMap<>();
    /** 已命中清单（规则间依赖：ANSWER-001 读 TOOL-001/REACT-002 的命中） */
    private final Map<String, List<RiskHit>> hitsByRule = new LinkedHashMap<>();

    public DetectContext(SessionRow session, List<TraceRow> rounds, PayloadAccessor payloads) {
        this(session, rounds, payloads, DetectConfig.defaults());
    }

    public DetectContext(SessionRow session, List<TraceRow> rounds, PayloadAccessor payloads, DetectConfig config) {
        this.session = session;
        this.rounds = rounds == null ? List.of() : List.copyOf(rounds);
        this.payloads = payloads;
        this.config = config == null ? DetectConfig.defaults() : config;
        for (TraceRow r : this.rounds) {
            byRound.put(r.round(), r);
        }
    }

    /** 本次检测配置（合并了配置表与代码默认值） */
    public DetectConfig config() {
        return config;
    }

    // ==================== 提问行便捷 ====================

    public SessionRow session() {
        return session;
    }

    public String sessionId() {
        return session == null ? "" : session.sessionId();
    }

    public String answer() {
        return session == null ? null : session.answer();
    }

    public String question() {
        return session == null ? "" : session.question();
    }

    /** 轮次数（session.rounds 终态回填；缺失回退瘦行行数） */
    public int roundsCount() {
        if (session != null && session.rounds() != null) {
            return session.rounds();
        }
        return rounds.size();
    }

    public int maxRound() {
        return rounds.isEmpty() ? 0 : rounds.get(rounds.size() - 1).round();
    }

    /** 按轮号取瘦行（无该轮返回 null） */
    public TraceRow round(int round) {
        return byRound.get(round);
    }

    /** 末轮瘦行（无轮次返回 null） */
    public TraceRow lastRound() {
        return rounds.isEmpty() ? null : rounds.get(rounds.size() - 1);
    }

    public List<TraceRow> rounds() {
        return rounds;
    }

    // ==================== 惰性 payload 视图（读取失败视为无数据，漏报不误报） ====================

    /** 本轮 tool_calls（output_data；仅工具轮有值），按轮缓存 */
    public List<ToolCallView> toolCalls(int round) {
        return toolCallsByTrace(round, round);
    }

    /** 第 observeRound 轮决策调用的工具，在第 observeRound+1 轮 input 中的观察（尾段 role=tool 消息） */
    public List<ToolObservation> observationsAfter(int observeRound) {
        Integer key = observeRound;
        if (obsCache.containsKey(key)) {
            return obsCache.get(key);
        }
        TraceRow next = round(observeRound + 1);
        List<ToolObservation> out = next == null || payloads == null
                ? List.of() : safeObs(next.traceId());
        obsCache.put(key, out);
        return out;
    }

    private List<ToolCallView> toolCallsByTrace(int cacheKey, int round) {
        Integer key = cacheKey;
        if (callCache.containsKey(key)) {
            return callCache.get(key);
        }
        TraceRow row = round(round);
        // 非工具轮（text/error 且无 tool_names）不碰 payload——text 轮 output 是回答文本，避免拉大字段
        boolean toolRound = row != null
                && ("tool".equals(row.roundType()) || (row.toolNames() != null && !row.toolNames().isEmpty()));
        List<ToolCallView> out = (row == null || payloads == null || !toolRound)
                ? List.of() : safeCalls(row.traceId());
        callCache.put(key, out);
        return out;
    }

    private List<ToolCallView> safeCalls(long traceId) {
        try {
            List<ToolCallView> v = payloads.toolCalls(traceId);
            return v == null ? List.of() : v;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ToolObservation> safeObs(long traceId) {
        try {
            List<ToolObservation> v = payloads.toolObservations(traceId);
            return v == null ? List.of() : v;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== 规则间依赖（ANSWER-001 用） ====================

    /** 服务侧逐规则登记命中（保持注册顺序） */
    public void addHits(String ruleId, List<RiskHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        hitsByRule.computeIfAbsent(ruleId, k -> new ArrayList<>()).addAll(hits);
    }

    /** 本问是否命中过任一规则（已评估部分） */
    public boolean hasHit(String... ruleIds) {
        for (String id : ruleIds) {
            List<RiskHit> hits = hitsByRule.get(id);
            if (hits != null && !hits.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 指定规则命中的定位轮号集合（V2：TOOL-005 排除 TOOL-001 命中轮、ANSWER-001 找最后失败轮用）。
     * round 为 null 的命中不计入。
     */
    public Set<Integer> hitRoundsOf(String ruleId) {
        Set<Integer> out = new HashSet<>();
        List<RiskHit> hits = hitsByRule.get(ruleId);
        if (hits != null) {
            for (RiskHit h : hits) {
                if (h.round() != null) {
                    out.add(h.round());
                }
            }
        }
        return out;
    }

    /** 已命中清单（只读视图） */
    public Map<String, List<RiskHit>> hitsByRule() {
        return Collections.unmodifiableMap(hitsByRule);
    }
}
