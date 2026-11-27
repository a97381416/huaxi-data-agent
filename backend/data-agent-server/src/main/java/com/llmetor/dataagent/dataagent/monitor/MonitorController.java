package com.llmetor.dataagent.dataagent.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.common.R;
import com.llmetor.dataagent.dataagent.monitor.detect.AnomalyDetectService;
import com.llmetor.dataagent.dataagent.monitor.detect.RiskStore;
import com.llmetor.dataagent.dataagent.monitor.dto.ConversationDetailVO;
import com.llmetor.dataagent.dataagent.monitor.dto.DetectRuleView;
import com.llmetor.dataagent.dataagent.monitor.dto.DetectSummary;
import com.llmetor.dataagent.dataagent.monitor.dto.OverviewVO;
import com.llmetor.dataagent.dataagent.monitor.dto.PageResult;
import com.llmetor.dataagent.dataagent.monitor.dto.RiskItem;
import com.llmetor.dataagent.dataagent.monitor.dto.RoundDetailVO;
import com.llmetor.dataagent.dataagent.monitor.dto.SearchItem;
import com.llmetor.dataagent.dataagent.monitor.dto.SessionPageItem;
import com.llmetor.dataagent.dataagent.monitor.dto.SessionTimelineVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控中心接口（M2/M5，设计 §5.2）：总览/会话列表/详情/单轮明细/快捷搜索/卡死运维/
 * AI 诊断 SSE/报告中心；Step3 异常检测（03 方案 §5）：风险列表/手动重检/规则注册表与配置。
 *
 * <p>全部走登录鉴权（HuaxiAuthFilter 覆盖 /monitor/*，不在白名单）；
 * 页面入口 adminOnly 由前端路由 meta 控制（同评测报告页约定，后端不做角色校验）。
 */
@Slf4j
@RestController
@RequestMapping("/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorQueryService monitorQueryService;
    private final MonitorDiagnoseService diagnoseService;
    private final MonitorReportStore reportStore;
    private final AnomalyDetectService detectService;
    private final RiskStore riskStore;
    private final com.llmetor.dataagent.dataagent.monitor.detect.DetectRerunService rerunService;
    private final com.llmetor.dataagent.dataagent.hitl.DecisionService decisionService;
    private final ObjectMapper objectMapper;

    /** 总览：KPI + 趋势（查分钟级聚合表）+ 失败会话 TOP + 进行中会话（卡死检测） */
    @GetMapping("/overview")
    public R<OverviewVO> overview(@RequestParam(defaultValue = "24h") String range) {
        return R.ok(monitorQueryService.overview(range));
    }

    /** 三期 HITL：专项指标（各类型触发次数、平均人工响应耗时、批准/拒绝/跳过率、核对型触发率） */
    @GetMapping("/hitl-metrics")
    public R<Map<String, Object>> hitlMetrics(@RequestParam(defaultValue = "24") int hours) {
        return R.ok(decisionService.getHitlMetrics(hours));
    }

    /**
     * 提问列表（主视图，一行 = 一次提问）：三 ID 组合筛选 + 会话摘要/状态/时间/耗时/问题模糊/
     * 轮数阈值，服务端分页。行内自带问题原文/回答预览/失败轮错误，无需进详情即可定位。
     * 排序：会话创建时间降序 → 提问创建时间升序。
     */
    @GetMapping("/sessions")
    public R<PageResult<SessionPageItem>> sessions(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String summary,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long minMs,
            @RequestParam(required = false) Long maxMs,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long minRounds,
            @RequestParam(required = false) Long maxRounds,
            @RequestParam(required = false) Boolean riskOnly,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String ruleId,
            @RequestParam(required = false) String detectorVersion,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return R.ok(monitorQueryService.sessions(userId, sessionId, conversationId, summary,
                status, minMs, maxMs, start, end, q, minRounds, maxRounds,
                riskOnly, severity, ruleId, detectorVersion, page, size));
    }

    // ==================== Step3 找到异常（03 方案 §5） ====================

    /** 风险列表（详情页自动检测卡数据源；severity desc, round asc） */
    @GetMapping("/sessions/{sessionId}/risks")
    public R<List<RiskItem>> sessionRisks(@PathVariable String sessionId) {
        return R.ok(riskStore.listBySession(sessionId));
    }

    /**
     * 该提问各规则已有诊断报告（06 调研成本纪律：规则级诊断前先看旧报告）——
     * 按 metrics_json 的 sessionId+ruleId 关联（零 DDL），每规则取最新一条 done 报告。
     * 无历史报告返回空数组（卡片不显示角标，点击直接诊断）。
     */
    @GetMapping("/sessions/{sessionId}/risk-reports")
    public R<List<MonitorDiagnoseService.RiskReportRef>> sessionRiskReports(@PathVariable String sessionId) {
        return R.ok(diagnoseService.riskReports(sessionId));
    }

    /**
     * 提问事件流时间轴（2026-11-20 排查视角改造）：一轮 LLM 调用拆成 🤖 决策事件 +
     * N 个 🔧 并发工具事件（结果按 tool_call_id 从下一轮 INPUT 配对回填），
     * 加 ❓ 提问起点与 💬 终答；llm 事件携带增量消息（本轮新传入上下文）。
     * 全量 INPUT 等重字段仍走 /monitor/rounds/{traceId} 懒加载。
     */
    @GetMapping("/sessions/{sessionId}/timeline")
    public R<SessionTimelineVO> sessionTimeline(@PathVariable String sessionId) {
        SessionTimelineVO vo = monitorQueryService.sessionTimeline(sessionId);
        if (vo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "提问不存在: " + sessionId);
        }
        return R.ok(vo);
    }

    /**
     * 手动重检（详情页「重新检测/立即检测」按钮；同步执行返回结果，<1s 量级；
     * 兜底调度失效的人工出口）。进行中提问 / 总开关关闭返回业务失败信息。
     */
    @PostMapping("/sessions/{sessionId}/detect")
    public R<DetectSummary> detect(@PathVariable String sessionId) {
        try {
            return R.ok(detectService.detect(sessionId));
        } catch (IllegalStateException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[monitor] 手动检测失败: sessionId={}, err={}", sessionId, e.getMessage());
            return R.fail("检测失败: " + e.getMessage());
        }
    }

    /** 规则注册表（「规则 N 条」弹层数据源：ID/名称/级别/说明/当前阈值/开关/默认阈值） */
    @GetMapping("/detect/rules")
    public R<Map<String, Object>> detectRules() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", com.llmetor.dataagent.dataagent.monitor.detect.DetectRuleRegistry.VERSION);
        data.put("rules", detectService.rulesView());
        return R.ok(data);
    }

    /**
     * 改单条规则 enabled/params/remark（adminOnly，前端路由 meta 控制；后端留痕 updated_by='admin'）。
     * 落配置表即时生效（影响后续检测，历史命中不自动重算）；remark 仅备注说明不参与判定；
     * 返回合并后配置供回显。
     */
    @PutMapping("/detect/rules/{ruleId}")
    public R<DetectRuleView> updateDetectRule(@PathVariable String ruleId,
                                              @RequestBody Map<String, Object> body) {
        try {
            Boolean enabled = body.get("enabled") instanceof Boolean b ? b : null;
            @SuppressWarnings("unchecked")
            Map<String, Object> params = body.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            String remark = body.get("remark") instanceof String s ? s : null;
            return R.ok(detectService.updateRule(ruleId, enabled, params, remark));
        } catch (IllegalArgumentException e) {
            return R.paramError(e.getMessage());
        } catch (Exception e) {
            log.warn("[monitor] 规则配置更新失败: ruleId={}, err={}", ruleId, e.getMessage());
            return R.fail(e.getMessage());
        }
    }

    /**
     * 批量重诊断（V2 §6，只读回放）：按 conversationIds / detectorVersionLt / from-to 时间圈定
     * 历史提问重跑 v2 规则（limit 默认 200 最大 1000）。受理 = 置 pending 后切片进现有
     * 检测队列串行执行；skipped = 非终态行数。绝不重放 Agent/SQL/API。
     */
    @PostMapping("/detect/rerun")
    public R<Map<String, Object>> rerunDetect(@RequestBody Map<String, Object> body) {
        try {
            List<String> conversationIds = body.get("conversationIds") instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : null;
            String detectorVersionLt = body.get("detectorVersionLt") instanceof String s ? s : null;
            String from = body.get("from") instanceof String s ? s : null;
            String to = body.get("to") instanceof String s ? s : null;
            Integer limit = body.get("limit") instanceof Number n ? n.intValue() : null;
            var result = rerunService.rerun(
                    new com.llmetor.dataagent.dataagent.monitor.detect.DetectRerunService.RerunRequest(
                            conversationIds, detectorVersionLt, from, to, limit));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accepted", result.accepted());
            data.put("skipped", result.skipped());
            return R.ok(data);
        } catch (IllegalArgumentException e) {
            return R.paramError(e.getMessage());
        } catch (IllegalStateException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[monitor] 批量重诊断受理失败: {}", e.getMessage());
            return R.fail("批量重诊断受理失败: " + e.getMessage());
        }
    }

    /** 批量重诊断进度（内存计数：受理/完成/失败/pending；进程重启清零，可选任务口径） */
    @GetMapping("/detect/rerun/status")
    public R<Map<String, Object>> rerunStatus() {
        var st = rerunService.status();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accepted", st.accepted());
        data.put("completed", st.completed());
        data.put("failed", st.failed());
        data.put("pending", st.pending());
        return R.ok(data);
    }

    /** 窗口详情：窗口聚合 + 全部提问 + 轮次瘦字段列表（前端按 ?sessionId=&round= 深链定位） */
    @GetMapping("/conversations/{conversationId}")
    public R<ConversationDetailVO> conversationDetail(@PathVariable String conversationId) {
        ConversationDetailVO vo = monitorQueryService.conversationDetail(conversationId);
        if (vo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + conversationId);
        }
        return R.ok(vo);
    }

    /** 单问最终回答全文（列表「最终回答」点击弹窗渲染 MD；未回填 404） */
    @GetMapping("/sessions/{sessionId}/answer")
    public R<String> sessionAnswer(@PathVariable String sessionId) {
        String answer = monitorQueryService.sessionAnswer(sessionId);
        if (answer == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该提问暂无最终回答: " + sessionId);
        }
        return R.ok(answer);
    }

    /** 单轮明细：payload 懒加载（includeTools 对齐 TraceQueryService 渲染契约） */
    @GetMapping("/rounds/{traceId}")
    public R<RoundDetailVO> roundDetail(@PathVariable long traceId,
                                        @RequestParam(required = false) Boolean includeTools) {
        RoundDetailVO vo = monitorQueryService.roundDetail(traceId, includeTools);
        if (vo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "轮次不存在: " + traceId);
        }
        return R.ok(vo);
    }

    /** 快捷查询：conversation_id 精确 → session_id 精确 → user_id → question 模糊 */
    @GetMapping("/search")
    public R<List<SearchItem>> search(@RequestParam String q) {
        return R.ok(monitorQueryService.search(q));
    }

    /** 运维操作：running 会话置为 interrupted（二次确认由前端确认框承担） */
    @PostMapping("/conversations/{conversationId}/interrupt")
    public R<Map<String, Object>> interrupt(@PathVariable String conversationId) {
        boolean updated = monitorQueryService.interrupt(conversationId);
        log.info("[monitor] 运维置为 interrupted: convId={}, updated={}", conversationId, updated);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("updated", updated);
        return R.ok(data);
    }

    // ==================== M5 AI 诊断与报告 ====================

    /**
     * 触发 AI 诊断（T5.2，POST SSE，设计 §6.5）：事件五类——thinking（思考片段）/
     * tool_call（工具调用+返回摘要）/ stage（markdown 报告正文增量，阶段结论实时更新）/
     * report_id（诊断完成报告落库后推送）/ done（收尾）；error 事件带失败原因。
     * 同目标并发触发返回 error 事件（服务端去重）。
     *
     * <p>诊断目标（2026-11-19 评审反馈：诊断针对某个提问）：sessionId 非空 = 提问级诊断
     * （聚焦该提问的问题/回答/轮次）；为空 = 窗口级全量体检。ruleId 非空 = 规则级定向诊断
     * （06 调研：风险卡「AI 诊断此风险」，规则命中证据自动注入，必须配合 sessionId）。
     *
     * <p>诊断模型 model（选哪个模型执行诊断，清单同聊天侧 /agent/models）：空 = 诊断默认
     * （yml 专用诊断模型，temperature=0）；非空 = agentx_chat_model 的 code
     * （解析语义同 DodoAgent：空/失效回退，报告 metrics 记真实生效 code）。
     */
    @PostMapping(value = "/diagnose/{conversationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> diagnose(@PathVariable String conversationId,
                                                  @RequestParam(required = false) String sessionId,
                                                  @RequestParam(required = false) String question,
                                                  @RequestParam(required = false) String ruleId,
                                                  @RequestParam(required = false) String model) {
        return diagnoseService.diagnoseStream(conversationId, sessionId, question, ruleId, model)
                .map(evt -> {
                    try {
                        return ServerSentEvent
                                .builder(objectMapper.writeValueAsString(evt))
                                .event(evt.type())
                                .build();
                    } catch (Exception e) {
                        return ServerSentEvent.builder("{\"type\":\"error\",\"content\":\"事件序列化失败\"}")
                                .event("error")
                                .build();
                    }
                });
    }

    /** 报告列表（type=single|daily、conversationId 过滤，服务端分页） */
    @GetMapping("/reports")
    public R<PageResult<MonitorReportStore.ReportItem>> reports(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return R.ok(reportStore.list(type, conversationId, page, size));
    }

    /** 报告详情（markdown 全文 + metrics_json） */
    @GetMapping("/reports/{id}")
    public R<MonitorReportStore.ReportDetail> reportDetail(@PathVariable long id) {
        MonitorReportStore.ReportDetail detail = reportStore.getById(id);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在: " + id);
        }
        return R.ok(detail);
    }
}
