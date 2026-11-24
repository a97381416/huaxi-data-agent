package com.llmetor.dataagent.dataagent.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.dataagent.eval.EvalChatModels;
import com.llmetor.dataagent.dataagent.eval.trace.TraceQueryTools;
import com.llmetor.dataagent.dataagent.model.AgentxChatModel;
import com.llmetor.dataagent.dataagent.model.ChatModelManager;
import com.llmetor.dataagent.dataagent.monitor.prompt.MonitorDiagnosePrompts;
import com.llmetor.miniagentx.agent.ReactAgent;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import com.llmetor.miniagentx.model.RunnableParams;
import com.llmetor.miniagentx.model.ThinkingMode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监控中心 AI 诊断服务（M5/T5.1+T5.2）：复用 eval 包 AgentDiagnoseService 的范式与约定——
 * ReactAgent + trace 查询工具（检索提问/全貌/单轮/首现）+ 归因四分类；构造不传 dataSource
 * （enableSession=false 语义）、enableTrace=false（不落库不写 trace，避免污染被测记录）、
 * temperature=0（可复现）。
 *
 * <p>与 eval 版两点差异：
 * <ol>
 *   <li><b>流式</b>：走 ReactAgent.stream()，内核事件映射为 SSE 三类事件——
 *       thinking（思考片段）/ tool_call（工具调用+返回摘要）/ stage（markdown 报告正文增量，
 *       即「阶段结论实时更新」）；结束后报告落 {@code agentx_monitor_report} 并推
 *       report_id / done 事件（设计 §6.5）。</li>
 *   <li><b>输出为 markdown 报告</b>（固定章节 + 归因行固定格式），全文落 detail_md，
 *       归因/置信度/影响轮正则提取进 metrics_json（列表页摘要与日报归因分布用）。</li>
 * </ol>
 *
 * <p>诊断目标（2026-11-19 评审反馈：诊断针对某个提问）：sessionId 非空 = 提问级诊断
 * （提示词聚焦该提问的问题/最终回答/轮次，报告 metrics_json 记 sessionId）；为空 = 窗口级全量体检。
 *
 * <p>并发约束：同一诊断目标（窗口+提问）同时只允许一个诊断流（内存 running 集合去重）；
 * 日报定时任务走 {@link #diagnoseOnce}（阻塞版，同 Agent 单例，call 每次独立执行器）。
 */
@Slf4j
@Service
public class MonitorDiagnoseService {

    private static final int MAX_ROUNDS = 50;
    /** 工具返回摘要截断长度（抽屉展示用，完整结果在 trace 里） */
    private static final int TOOL_RESULT_PREVIEW = 200;
    /** 提问级诊断：终答摘要进提示词的截断长度 */
    private static final int ANSWER_PROMPT_CHARS = 300;
    /** v2 报告头「诊断时间」格式（appendHeaderInfo 注入） */
    private static final DateTimeFormatter HEADER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ObjectMapper objectMapper;
    private final TraceQueryTools traceQueryTools;
    private final ThinkingMode thinkingMode;
    private final MonitorReportStore reportStore;
    private final com.llmetor.dataagent.dataagent.monitor.detect.RiskStore riskStore;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    /** 对话模型管理（agentx_chat_model）：诊断可选模型与聊天侧同一套清单/解析语义 */
    private final ChatModelManager chatModelManager;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;
    @Value("${spring.ai.openai.base-url:https://api.minimax.chat}")
    private String baseUrl;
    @Value("${data-agent.agent-eval.diagnose-model:${spring.ai.openai.chat.options.model:MiniMax-M3.1-Flash}}")
    private String diagnoseModel;
    /** 报告模板版本：v2 = 七段骨架（07 文档 V3 融合模板）；v1 = 回退旧四段模板（PromptSet 整套切） */
    @Value("${data-agent.monitor.diagnose-template:v2}")
    private String diagnoseTemplate;

    private ChatModel chatModel;
    /** 当前启用的报告模板四件套（系统提示词 + 三入口用户模板，同版本配套） */
    private MonitorDiagnosePrompts.PromptSet prompts;
    /** v2 才注入报告头部信息（触发来源/诊断时间）；v1 回退时保持旧提示词纯净 */
    private boolean headerInfoEnabled;

    /** 同目标并发去重（前端双击/多窗口同时触发同一目标诊断） */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public MonitorDiagnoseService(ObjectMapper objectMapper,
                                  TraceQueryTools traceQueryTools,
                                  MonitorReportStore reportStore,
                                  com.llmetor.dataagent.dataagent.monitor.detect.RiskStore riskStore,
                                  javax.sql.DataSource dataSource,
                                  ChatModelManager chatModelManager,
                                  @Value("${data-agent.agent.thinking-mode:REASONING_CONTENT}")
                                  ThinkingMode thinkingMode) {
        this.objectMapper = objectMapper;
        this.traceQueryTools = traceQueryTools;
        this.reportStore = reportStore;
        this.riskStore = riskStore;
        // 刻意注入 DataSource 自建 JdbcTemplate（同 MonitorQueryService：避免拿到 pgVectorJdbcTemplate）
        this.jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        this.chatModelManager = chatModelManager;
        this.thinkingMode = thinkingMode;
    }

    @PostConstruct
    void init() {
        // 与 AgentDiagnoseService 同款口径：专用模型 temperature=0、读超时 300s，作为「诊断默认」与兜底。
        // Agent 改为每次诊断按所选模型构建（见 buildDiagnoseAgent）——诊断抽屉可选模型执行诊断，
        // 同 DodoAgent 每请求重建范式；chatModel 仍单例复用。
        this.chatModel = EvalChatModels.build(apiKey, baseUrl, diagnoseModel, Duration.ofSeconds(300));
        this.prompts = MonitorDiagnosePrompts.PromptSet.of(diagnoseTemplate);
        this.headerInfoEnabled = !"v1".equalsIgnoreCase(diagnoseTemplate);
        log.info("[monitor] diagnose agent factory initialized | model={} template={} maxRounds={} thinkingMode={}",
                diagnoseModel, diagnoseTemplate, MAX_ROUNDS, thinkingMode);
    }

    /**
     * 按所选模型构建诊断 Agent（沿用 M8-D01 约定：不传 dataSource/sessionStore、
     * enableTrace=false 不落库不写 trace、enableCompaction 内存 offload）。
     */
    private ReactAgent buildDiagnoseAgent(ChatModel model) {
        return ReactAgent.builder()
                .chatModel(model)
                .instructions(prompts.instructions())
                .tools(List.of(ToolCallbacks.from(traceQueryTools)))
                .thinkingMode(thinkingMode)
                .maxRounds(MAX_ROUNDS)
                .enableTrace(false)
                .enableCompaction(true)
                .build();
    }

    /**
     * SSE 诊断事件（前端诊断抽屉消费；type 即 SSE 事件名小写；reportId 用 String 避免前端 JS 大数精度丢失）。
     */
    public record DiagnoseEvent(String type, String content, String toolName, String arguments,
                                Boolean success, String reportId, Long elapsedMs) {

        static DiagnoseEvent thinking(String delta) {
            return new DiagnoseEvent("thinking", delta, null, null, null, null, null);
        }

        static DiagnoseEvent toolCall(String toolName, String arguments, String result, Boolean success) {
            return new DiagnoseEvent("tool_call", preview(result), toolName, arguments, success, null, null);
        }

        static DiagnoseEvent stage(String delta) {
            return new DiagnoseEvent("stage", delta, null, null, null, null, null);
        }

        static DiagnoseEvent error(String message) {
            return new DiagnoseEvent("error", message, null, null, null, null, null);
        }

        static DiagnoseEvent reportSaved(long reportId, long elapsedMs) {
            return new DiagnoseEvent("report_id", null, null, null, null, String.valueOf(reportId), elapsedMs);
        }

        static DiagnoseEvent done(long elapsedMs) {
            return new DiagnoseEvent("done", null, null, null, null, null, elapsedMs);
        }

        private static String preview(String text) {
            if (text == null) {
                return null;
            }
            String oneLine = text.replace("\r", " ").replace("\n", " ").trim();
            return oneLine.length() > TOOL_RESULT_PREVIEW ? oneLine.substring(0, TOOL_RESULT_PREVIEW) + "…" : oneLine;
        }
    }

    /**
     * 流式诊断（T5.2）：SSE 事件流 thinking / tool_call / stage → report_id → done。
     * sessionId 非空 = 提问级诊断（聚焦该提问），为空 = 窗口级全量体检；
     * ruleId 非空 = 规则级定向诊断（06 调研：入口为风险卡「AI 诊断此风险」，规则证据自动注入）。
     * model 决定用哪个模型执行诊断：空 = 诊断默认（yml 专用诊断模型，temperature=0）；
     * 非空 = agentx_chat_model 的 code（解析语义同 DodoAgent：空/失效回退，metrics 记真实生效 code）。
     * 报告落库失败不炸流（error 事件带原因，明细全文仍在 stage 增量里）。
     */
    public Flux<DiagnoseEvent> diagnoseStream(String conversationId, String sessionId, String question,
                                              String ruleId, String model) {
        if (conversationId == null || conversationId.isBlank()) {
            return Flux.just(DiagnoseEvent.error("conversationId 不能为空"));
        }
        boolean ruleMode = ruleId != null && !ruleId.isBlank();
        if (ruleMode && (sessionId == null || sessionId.isBlank())) {
            return Flux.just(DiagnoseEvent.error("规则级诊断必须带 sessionId（风险挂在具体提问下）"));
        }
        // 规则级前置取命中行（不在流里做：查不到直接 error 事件，避免起一个注定跑偏的 ReAct）
        com.llmetor.dataagent.dataagent.monitor.dto.RiskItem ruleRisk = null;
        if (ruleMode) {
            ruleRisk = findRisk(sessionId, ruleId.trim());
            if (ruleRisk == null) {
                return Flux.just(DiagnoseEvent.error("未找到该提问下规则 " + ruleId + " 的命中记录（可能已重检消失）"));
            }
        }
        String target = diagnoseTargetKey(conversationId, sessionId, ruleId);
        if (!running.add(target)) {
            return Flux.just(DiagnoseEvent.error("该目标已有诊断进行中，请等待完成后再试"));
        }
        long start = System.currentTimeMillis();
        StringBuilder report = new StringBuilder();
        StringBuilder errorRef = new StringBuilder();

        // 提问级：查 monitor_session 回填 Q 序号/问题/最终回答，提示词聚焦该提问；
        // 风险清单只查一次——提问级用户提示词与窗口级共用同一取数口径
        SessionTarget st = sessionId == null || sessionId.isBlank() ? null : loadSessionTarget(conversationId, sessionId);
        String riskSection = st != null ? riskContextSection(sessionId) : "";
        String userPrompt;
        if (ruleMode) {
            userPrompt = buildRuleUserPrompt(conversationId, st, sessionId, ruleRisk);
        } else if (st != null) {
            userPrompt = buildSessionUserPrompt(conversationId, st, sessionId, riskSection);
        } else {
            userPrompt = prompts.userTemplate().formatted(conversationId);
        }
        // 用户补充的排查问题（可选）：有值时定向诊断
        if (question != null && !question.isBlank()) {
            userPrompt += "\n\n用户补充的排查问题（重点围绕该问题定位根因，其余项从简）：\n" + question.trim();
        }
        // v2 报告模板：注入报告头事实（触发来源/诊断时间），模型照填报告头
        if (headerInfoEnabled) {
            userPrompt = appendHeaderInfo(userPrompt, conversationId, sessionId,
                    ruleMode ? ruleId.trim() : null, question);
        }

        // 诊断模型解析（同 DodoAgent 语义）：pickRow 取真实生效行（空/失效→null→yml 专用诊断模型）；
        // metrics 记真实 code 而非请求原样，避免回退后账实不符
        AgentxChatModel modelRow = model == null || model.isBlank() ? null : chatModelManager.pickRow(model.trim());
        ChatModel chosen = modelRow != null ? chatModelManager.resolve(modelRow.getCode(), chatModel) : chatModel;
        String metricsModel = modelRow != null ? modelRow.getCode() : null;
        if (modelRow == null && model != null && !model.isBlank()) {
            log.warn("[monitor] 诊断模型 code 未命中或已停用，回退诊断默认: model={}", model);
        }

        Flux<DiagnoseEvent> main = buildDiagnoseAgent(chosen)
                .stream(userPrompt, params(conversationId))
                .concatMap(evt -> mapEvent(evt, report, errorRef))
                .onErrorResume(e -> {
                    log.warn("[monitor] 诊断流执行异常 convId={}", conversationId, e);
                    errorRef.append(safeMessage(e));
                    return Flux.just(DiagnoseEvent.error("诊断执行失败: " + safeMessage(e)));
                });

        // 流终态后：报告落库 + 推 report_id/done（concatWith 保证在所有 stage 增量之后）
        String ruleIdFinal = ruleMode ? ruleId.trim() : null;
        final com.llmetor.dataagent.dataagent.monitor.dto.RiskItem finalRuleRisk = ruleRisk;
        String finalQuestion = (question != null && !question.isBlank())
                ? question.trim()
                : (st == null ? null : st.question());
        String finalSessionId = (sessionId != null && !sessionId.isBlank())
                ? sessionId.trim()
                : (st == null ? null : st.sessionId());

        Flux<DiagnoseEvent> tail = Mono.fromCallable(() ->
                        finalizeReport(conversationId, finalSessionId,
                                finalQuestion, ruleIdFinal, finalRuleRisk,
                                report.toString(), errorRef.toString(), metricsModel, start))
                .flatMapMany(Flux::fromIterable)
                .onErrorResume(e -> {
                    log.warn("[monitor] 诊断报告落库失败 convId={}", conversationId, e);
                    return Flux.just(DiagnoseEvent.error("报告落库失败: " + safeMessage(e)),
                            DiagnoseEvent.done(System.currentTimeMillis() - start));
                });

        return main.concatWith(tail)
                .doFinally(sig -> running.remove(target));
    }

    /** 阻塞版诊断（日报定时任务用）：sessionId 非空 = 提问级；走诊断默认模型；返回 markdown 全文并落库（created_by=auto） */
    public String diagnoseOnce(String conversationId, String sessionId) {
        long start = System.currentTimeMillis();
        SessionTarget st = sessionId == null || sessionId.isBlank() ? null : loadSessionTarget(conversationId, sessionId);
        String userPrompt;
        if (st != null) {
            userPrompt = buildSessionUserPrompt(conversationId, st, sessionId, riskContextSection(sessionId));
        } else {
            userPrompt = prompts.userTemplate().formatted(conversationId);
        }
        if (headerInfoEnabled) {
            userPrompt = appendHeaderInfo(userPrompt, conversationId, sessionId, null, null);
        }
        String text = buildDiagnoseAgent(chatModel)
                .call(userPrompt, params(conversationId)).getAnswer();
        long reportId = saveReport("single", conversationId,
                st == null ? null : st.sessionId(), st == null ? null : st.question(), null, text, "", null);
        log.info("[monitor] 轻量诊断完成 convId={} sessionId={} reportId={} durationMs={}",
                conversationId, sessionId, reportId, System.currentTimeMillis() - start);
        return text;
    }

    // ── 报告头注入（v2 模板：触发来源/诊断时间是运行期事实，模型不知道，由后端注入照填） ──

    /**
     * 用户消息末尾追加「报告头部信息」段：会话 ID / 触发来源（规则级带规则 ID，提问级/窗口级按档位；
     * question 非空追加「用户反馈」，提示模型异常类型可判「用户反馈语义类」）/ 诊断时间。
     */
    private String appendHeaderInfo(String prompt, String conversationId, String sessionId,
                                    String ruleId, String question) {
        String trigger = ruleId != null && !ruleId.isBlank()
                ? "规则级定向诊断（规则 " + ruleId + "）"
                : (sessionId == null || sessionId.isBlank() ? "窗口级全量体检" : "提问级全链路诊断");
        if (question != null && !question.isBlank()) {
            trigger += " + 用户反馈（用户补充的排查诉求）";
        }
        String sessionLine = sessionId == null || sessionId.isBlank()
                ? conversationId + " /（窗口级）"
                : conversationId + " / " + sessionId;
        return prompt + "\n\n报告头部信息（报告头按此原样填写，不要改动）：\n"
                + "- 会话：" + sessionLine + "\n"
                + "- 触发来源：" + trigger + "\n"
                + "- 诊断时间：" + LocalDateTime.now().format(HEADER_TIME) + "\n";
    }

    // ── 提问级诊断目标 ──

    /**
     * 提问级诊断用户消息（T8，03 方案 §7）：模板格式化 + 注入 Step3 自动检测风险清单——
     * 「已命中规则 REACT-003@R5-R7…优先核验」，让诊断 Agent 少走弯路、报告可对照规则复核。
     */
    private String buildSessionUserPrompt(String conversationId, SessionTarget st, String sessionId, String riskSection) {
        String answerLine = st.answer() == null || st.answer().isBlank()
                ? "（该提问暂无最终回答——可能执行失败/中断，重点排查哪一轮出的错）"
                : "最终回答摘要：" + abbreviate(st.answer(), ANSWER_PROMPT_CHARS);
        String prompt = prompts.sessionTemplate()
                .formatted(conversationId, st.seq(), sessionId, st.question(), answerLine);
        if (riskSection != null && !riskSection.isEmpty()) {
            prompt += "\n" + riskSection;
        }
        return prompt;
    }

    /** 风险清单段落（agentx_monitor_risk 命中行，severity→round 排序）；未检测/无命中返回空串 */
    private String riskContextSection(String sessionId) {
        try {
            List<com.llmetor.dataagent.dataagent.monitor.dto.RiskItem> risks = riskStore.listBySession(sessionId);
            if (risks.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder(
                    "自动检测已命中以下风险（规则引擎预检结果，请优先核验这些轮次并在证据链中对照复核）：\n");
            for (com.llmetor.dataagent.dataagent.monitor.dto.RiskItem r : risks) {
                sb.append("- ").append(r.ruleId()).append(' ').append(r.title())
                        .append("【").append(severityCn(r.severity())).append("】");
                if (r.round() != null) {
                    sb.append("@R").append(r.round());
                }
                if (r.roundsHit() != null && !r.roundsHit().isEmpty()) {
                    sb.append("@R").append(r.roundsHit().stream()
                            .map(String::valueOf).collect(java.util.stream.Collectors.joining(",R")));
                }
                sb.append("：").append(r.detail()).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("[monitor] 风险清单注入失败（忽略，不影响诊断）: {}", e.getMessage());
            return "";
        }
    }

    /** V2-D9 三档口径：与前端、RiskStore 统一（异常/待人工确认/风险提示） */
    private static String severityCn(String severity) {
        return switch (severity == null ? "" : severity) {
            case "high" -> "异常";
            case "medium" -> "待人工确认";
            default -> "风险提示";
        };
    }

    // ── 规则级定向诊断（06 调研：风险卡「AI 诊断此风险」，上下文自动注入） ──

    /** 查该提问下指定规则的命中行；查不到返回 null（调用方直接 error 事件） */
    private com.llmetor.dataagent.dataagent.monitor.dto.RiskItem findRisk(String sessionId, String ruleId) {
        try {
            return riskStore.listBySession(sessionId).stream()
                    .filter(r -> ruleId.equals(r.ruleId()))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("[monitor] 规则命中行查询失败: sessionId={}, ruleId={}, err={}", sessionId, ruleId, e.getMessage());
            return null;
        }
    }

    /**
     * 规则级诊断用户消息：模板 {xxx} 占位 replace 填充（不用 String.format——
     * 证据 JSON/问题原文里可能有 % 字面量），注入规则 + 证据明细 + 所属提问上下文。
     */
    private String buildRuleUserPrompt(String conversationId, SessionTarget st, String sessionId,
                                       com.llmetor.dataagent.dataagent.monitor.dto.RiskItem risk) {
        String roundsText = risk.roundsHit() != null && !risk.roundsHit().isEmpty()
                ? risk.roundsHit().stream().map(r -> "R" + r).collect(java.util.stream.Collectors.joining(","))
                : (risk.round() == null ? "（未标注）" : "R" + risk.round());
        String evidence;
        try {
            evidence = risk.evidence() == null || risk.evidence().isEmpty()
                    ? "（无）" : objectMapper.writeValueAsString(risk.evidence());
        } catch (Exception e) {
            evidence = "（解析失败）";
        }
        String answerLine = st == null || st.answer() == null || st.answer().isBlank()
                ? "最终回答摘要：（该提问暂无最终回答——可能执行失败/中断，重点排查哪一轮出的错）"
                : "最终回答摘要：" + abbreviate(st.answer(), ANSWER_PROMPT_CHARS);
        return prompts.ruleTemplate()
                .replace("{convId}", conversationId)
                .replace("{seq}", st == null ? "?" : String.valueOf(st.seq()))
                .replace("{sessionId}", sessionId)
                .replace("{ruleId}", risk.ruleId())
                .replace("{ruleTitle}", risk.title() == null ? "" : risk.title())
                .replace("{severityCn}", severityCn(risk.severity()))
                .replace("{roundsText}", roundsText)
                .replace("{detail}", risk.detail() == null ? "（无）" : risk.detail())
                .replace("{evidence}", abbreviate(evidence, EVIDENCE_PROMPT_CHARS))
                .replace("{question}", st == null ? "（提问行缺失）" : st.question())
                .replace("{answerLine}", answerLine);
    }

    /** 规则级诊断：证据 JSON 进提示词的截断长度（防超长 evidence 撑爆首条消息） */
    private static final int EVIDENCE_PROMPT_CHARS = 800;

    /**
     * 该提问各规则已有诊断报告（成本纪律：卡片先看旧报告，避免重复诊断）——
     * 按 metrics_json @> {"sessionId": sid} 查 single 报告，ruleId 非空的按规则聚合取最新一条。
     * 规则级诊断是 V2 后新增（历史报告 metrics 无 ruleId，自然不返回）。
     */
    public List<RiskReportRef> riskReports(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        String cond;
        try {
            cond = objectMapper.writeValueAsString(java.util.Map.of("sessionId", sessionId));
        } catch (Exception e) {
            return List.of();
        }
        Map<String, RiskReportRef> latest = new LinkedHashMap<>();
        try {
            jdbc.query("""
                    SELECT id, summary_md, metrics_json, created_at FROM agentx_monitor_report
                    WHERE type = 'single' AND status = 'done' AND metrics_json @> ?::jsonb
                    ORDER BY created_at DESC LIMIT 50
                    """, rs -> {
                String metrics = rs.getString("metrics_json");
                String ruleId = null;
                try {
                    com.fasterxml.jackson.databind.JsonNode node = metrics == null ? null
                            : objectMapper.readTree(metrics).path("ruleId");
                    ruleId = node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
                } catch (Exception ignore) {
                    // metrics 非法 JSON：无规则关联，跳过
                }
                if (ruleId == null || latest.containsKey(ruleId)) {
                    return;
                }
                String summary = rs.getString("summary_md");
                // 摘要首行即「归因: …」行（saveReport 写入时放最前）
                String firstLine = summary == null ? "" : summary.strip().lines().findFirst().orElse("");
                latest.put(ruleId, new RiskReportRef(ruleId, rs.getLong("id"), firstLine,
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toString()));
            }, cond);
        } catch (Exception e) {
            log.warn("[monitor] 规则报告查询失败（返回空，不影响卡片渲染）: sessionId={}, err={}", sessionId, e.getMessage());
            return List.of();
        }
        return List.copyOf(latest.values());
    }

    /** 风险卡「已有报告」角标数据（ruleId → 最新报告，卡片按 ruleId 关联） */
    public record RiskReportRef(String ruleId, long reportId, String summary, String createdAt) {
    }

    /** 提问级诊断目标（monitor_session 一行的关键字段） */
    record SessionTarget(String sessionId, int seq, String question, String answer) {    }

    /** 查 monitor_session 组装提问诊断目标；查不到返回 null（调用方降级窗口级诊断） */
    private SessionTarget loadSessionTarget(String conversationId, String sessionId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT session_id, seq, question, answer FROM agentx_monitor_session " +
                            "WHERE conversation_id = ? AND session_id = ? LIMIT 1",
                    conversationId, sessionId);
            if (rows.isEmpty()) {
                log.warn("[monitor] 提问级诊断未命中 session 行，降级窗口级: convId={}, sessionId={}",
                        conversationId, sessionId);
                return null;
            }
            Map<String, Object> r = rows.get(0);
            return new SessionTarget(str(r.get("session_id")),
                    ((Number) r.get("seq")).intValue(),
                    str(r.get("question")), str(r.get("answer")));
        } catch (Exception e) {
            log.warn("[monitor] 提问级诊断目标加载失败，降级窗口级: convId={}, sessionId={}",
                    conversationId, sessionId, e);
            return null;
        }
    }

    private static String diagnoseTargetKey(String conversationId, String sessionId, String ruleId) {
        return conversationId + ":" + (sessionId == null ? "" : sessionId) + ":" + (ruleId == null ? "" : ruleId);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String abbreviate(String text, int max) {
        String oneLine = text.replace("\r", " ").replace("\n", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }

    // ── 事件映射与落库 ──

    private Flux<DiagnoseEvent> mapEvent(AgentStreamEvent evt, StringBuilder report, StringBuilder errorRef) {
        return switch (evt.getType()) {
            case THINKING -> Flux.just(DiagnoseEvent.thinking(evt.getContent()));
            // 工具开始给参数摘要；结束补结果摘要（前端按 toolCallId 聚合成一行）
            case TOOL_START -> Flux.just(DiagnoseEvent.toolCall(evt.getToolName(), evt.getArguments(), null, null));
            case TOOL_END -> Flux.just(DiagnoseEvent.toolCall(evt.getToolName(), null, evt.getResult(), evt.getSuccess()));
            case TEXT -> {
                if (evt.getContent() != null) {
                    report.append(evt.getContent());
                }
                yield Flux.just(DiagnoseEvent.stage(evt.getContent()));
            }
            case ERROR -> {
                String message = evt.getMessage() != null ? evt.getMessage() : evt.getDetail();
                if (message != null) {
                    errorRef.append(message);
                }
                yield Flux.just(DiagnoseEvent.error(message != null ? message : "诊断执行出错"));
            }
            case PAUSED, COMPLETE -> Flux.empty();  // 诊断不可中断；终态由 tail 处理
        };
    }

    /** 报告落库：status 按产物与错误标记 done/failed，推 report_id（落库成功才有）+ done */
    private List<DiagnoseEvent> finalizeReport(String conversationId, String sessionId, String question,
                                               String ruleId, com.llmetor.dataagent.dataagent.monitor.dto.RiskItem ruleRisk,
                                               String reportText, String error,
                                               String modelCode, long start) {
        List<DiagnoseEvent> events = new ArrayList<>();
        long elapsed = System.currentTimeMillis() - start;
        boolean empty = reportText == null || reportText.isBlank();
        String status = empty || !error.isEmpty() ? "failed" : "done";
        long reportId = saveReport("single", conversationId, sessionId, question, ruleId, ruleRisk,
                empty ? "" : reportText, error, modelCode);
        if (reportId > 0) {
            events.add(DiagnoseEvent.reportSaved(reportId, elapsed));
        }
        events.add(DiagnoseEvent.done(elapsed));
        return events;
    }

    long saveReport(String type, String conversationId, String sessionId, String question,
                    String ruleId, String reportText, String error, String modelCode) {
        return saveReport(type, conversationId, sessionId, question, ruleId, null, reportText, error, modelCode);
    }

    /**
     * 报告落库：summary_md = 归因行 + 影响轮；metrics_json =
     * attribution/confidence/affectedRound/anomalyType + sessionId/question/ruleId/ruleTitle/diagnoseScope
     */
    long saveReport(String type, String conversationId, String sessionId, String question,
                    String ruleId, com.llmetor.dataagent.dataagent.monitor.dto.RiskItem ruleRisk,
                    String reportText, String error, String modelCode) {
        try {
            String status = (reportText == null || reportText.isBlank()) || !error.isBlank()
                    ? "failed" : "done";
            String attribution = DiagnoseReportMetrics.extract(reportText, DiagnoseReportMetrics.ATTRIBUTION);
            String confidence = DiagnoseReportMetrics.extract(reportText, DiagnoseReportMetrics.CONFIDENCE);
            String affectedRound = DiagnoseReportMetrics.extract(reportText, DiagnoseReportMetrics.AFFECTED_ROUND);
            String anomalyType = DiagnoseReportMetrics.extract(reportText, DiagnoseReportMetrics.ANOMALY_TYPE);
            String summary = "归因: " + (attribution.isEmpty() ? "未识别" : attribution)
                    + (confidence.isEmpty() ? "" : " (置信度: " + confidence + ")")
                    + (affectedRound.isEmpty() || "0".equals(affectedRound) ? "" : " 影响轮: R" + affectedRound)
                    + (anomalyType.isEmpty() ? "" : " | 类型: " + anomalyType)
                    + (error.isBlank() ? "" : " | 诊断异常: " + error);
            String summaryMd = summary.trim();
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("attribution", attribution.isEmpty() ? null : attribution);
            metrics.put("confidence", confidence.isEmpty() ? null : confidence);
            metrics.put("affectedRound", affectedRound.isEmpty() ? null : Integer.valueOf(affectedRound));
            metrics.put("anomalyType", anomalyType.isEmpty() ? null : anomalyType);
            metrics.put("error", error.isEmpty() ? null : error);
            metrics.put("sessionId", sessionId == null || sessionId.isBlank() ? null : sessionId);
            metrics.put("question", question == null || question.isBlank() ? null : question);
            metrics.put("ruleId", ruleId == null || ruleId.isBlank() ? null : ruleId);
            if (ruleRisk != null) {
                metrics.put("ruleTitle", ruleRisk.title());
                metrics.put("ruleSeverity", ruleRisk.severity());
                metrics.put("ruleRound", ruleRisk.round());
            }
            String scope = (ruleId != null && !ruleId.isBlank()) ? "rule"
                    : ((sessionId != null && !sessionId.isBlank()) ? "session" : "window");
            metrics.put("diagnoseScope", scope);
            metrics.put("model", modelCode == null || modelCode.isBlank() ? null : modelCode);
            String detailMd = error.isBlank()
                    ? (reportText == null ? "" : reportText)
                    : (reportText == null ? "" : reportText) + "\n\n> ⚠ 诊断执行异常: " + error;
            return reportStore.insert(type, conversationId, null, null, status,
                    summaryMd, detailMd, objectMapper.writeValueAsString(metrics), "manual");
        } catch (Exception e) {
            log.warn("[monitor] 报告落库失败 convId={}", conversationId, e);
            return -1;
        }
    }

    private static RunnableParams params(String conversationId) {
        // conversationId 必传：内核硬校验非空；本 Agent 无 dataSource 不落库，仅作压缩 offload 内存键
        return RunnableParams.builder().conversationId(conversationId).build();
    }

    private static String safeMessage(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
