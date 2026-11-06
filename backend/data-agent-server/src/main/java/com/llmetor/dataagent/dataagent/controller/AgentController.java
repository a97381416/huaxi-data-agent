package com.llmetor.dataagent.dataagent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.auth.service.HuaxiAuthService;
import com.llmetor.dataagent.common.R;
import com.llmetor.dataagent.dataagent.agent.DodoAgent;
import com.llmetor.dataagent.dataagent.dto.ChatRequest;
import com.llmetor.dataagent.dataagent.stream.LiveStreamRegistry;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 流式问答总入口（M3：事件序号 + 断线续传 + 执行/连接解耦 + Pause/Resume + takeOver）。
 *
 * <p>POST /agent/stream 的四条路径（对齐原版 AgentController）：
 * <ol>
 *   <li><b>recovery 恢复</b>：recovery=true 不新起执行，接回该会话进行中的流，
 *       从 startSeq 补发续传（属主校验：非本人流 403、无进行中流 404）</li>
 *   <li><b>takeOver 顶掉</b>：会话执行中来了新消息——打断旧回答 → 轮询 ≤3s 等流停 →
 *       丢弃旧断点快照（半截回答保留在历史里）→ 随后全新执行；停不下来返回 409</li>
 *   <li><b>resume 恢复执行</b>：存在中断快照（此前被 /agent/stop 打断）→ 不新起，
 *       从快照续跑；query 为空 = 本地工具重放，非空 = 澄清语注入由模型自决策</li>
 *   <li><b>全新执行</b>：注册进 LiveStreamRegistry 后台驱动，前端断开不影响执行</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentController {

    /** takeOver 等待旧流停止的轮询上限：30 × 100ms = 3s（docs/03 M3 口径） */
    private static final int TAKEOVER_POLL_TIMES = 30;
    private static final long TAKEOVER_POLL_INTERVAL_MS = 100;

    private final DodoAgent dodoAgent;
    private final HuaxiAuthService authService;
    private final LiveStreamRegistry liveStreams;
    private final ObjectMapper objectMapper;

    /**
     * 流式问答（POST SSE，事件名=类型小写，id=事件自增序号，data=事件 JSON）。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest request) {
        String conversationId = StringUtils.hasText(request.conversationId())
                ? request.conversationId()
                : "dodo_conv_" + UUID.randomUUID();

        // 拦截器已校验登录，走到这里 userId 必非空；为空说明 SaTokenContext 异常，fail-fast
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            log.error("[data-agent] 拦截器已通过但 userId 为空，SaTokenContext 异常 | convId={}", conversationId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户身份获取失败，请重新登录");
        }

        // ① 恢复请求：不新起执行，接回该会话进行中的流（断线续传）
        if (Boolean.TRUE.equals(request.recovery())) {
            requireActiveOwned(conversationId, userId);
            long startSeq = request.startSeq() == null ? 0 : request.startSeq();
            log.info("[data-agent] 恢复流式: convId={}, userId={}, startSeq={}", conversationId, userId, startSeq);
            Flux<ServerSentEvent<AgentStreamEvent>> attached = liveStreams.attach(conversationId, startSeq);
            if (attached == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前会话没有进行中的回答");
            }
            return attached.map(this::toSse);
        }

        // ② 执行中来了新消息（对齐豆包交互）：打断旧回答、丢弃断点，随后走全新/恢复执行
        if (liveStreams.isActive(conversationId)) {
            takeOverActiveStream(conversationId);
        }

        Flux<AgentStreamEvent> source;
        // ③ 存在中断快照 → 恢复执行（本次 query 作为 resumeQuery 传入，空 = 纯恢复）
        if (dodoAgent.hasInterruptedState(conversationId)) {
            source = dodoAgent.resumeStream(conversationId, userId, request.query(),
                    request.fileIds(), isOnline(request));
        } else {
            // ④ 全新执行
            if (!StringUtils.hasText(request.query())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 不能为空");
            }
            log.info("[data-agent] 收到查询: convId={}, userId={}, query={}, fileIds={}, online={}",
                    conversationId, userId, request.query(),
                    request.fileIds() == null ? 0 : request.fileIds().size(), isOnline(request));
            source = dodoAgent.stream(request.query(), conversationId, userId,
                    request.fileIds(), isOnline(request));
        }

        // 流级兜底：内核异常转 ERROR + COMPLETE 事件（注册表 doFinally 会按流终止摘除）
        final String convId = conversationId;
        source = source.onErrorResume(e -> {
            log.error("[data-agent] 流执行异常 convId={}", convId, e);
            return Flux.just(
                    AgentStreamEvent.error("STREAM_ERROR", safeMessage(e), null),
                    AgentStreamEvent.complete(0, 0, convId, ""));
        });

        // 注册并后台驱动：返回给前端的和重连拿到的是同一个可重放流
        Flux<ServerSentEvent<AgentStreamEvent>> registered = liveStreams.register(conversationId, userId, source);
        return registered.map(this::toSse);
    }

    /**
     * 查询指定会话是否有进行中的流（前端进页面时探测，决定是否发恢复请求）。
     */
    @GetMapping("/stream/status")
    public R<Map<String, Object>> streamStatus(@RequestParam String conversationId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("active", liveStreams.isActive(conversationId));
        return R.ok(data);
    }

    /**
     * Agent 能力探测（M6：联网开关置灰依据；M7 增补三层装配速览）。
     * onlineAvailable=false 说明后端未配置 tavily.api-key，开了开关也不会注入联网工具。
     * toolLayers：resident=首轮即带的常驻工具（含 tool_search/context_reload）、
     * deferred=延迟层工具（LLM 经 tool_search 发现后可用）。
     */
    @GetMapping("/capabilities")
    public R<Map<String, Object>> capabilities() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("onlineAvailable", dodoAgent.isOnlineAvailable());
        data.put("toolLayers", dodoAgent.toolLayers());
        return R.ok(data);
    }

    /**
     * 中断指定会话的流式输出（持久化快照到 agentx_pause_state，支持后续 resume 继续）。
     */
    @GetMapping("/stop")
    public R<Map<String, Object>> stop(@RequestParam String conversationId) {
        boolean interrupted = dodoAgent.interrupt(conversationId, "用户请求中断");
        boolean hasState = dodoAgent.hasInterruptedState(conversationId);
        log.info("[data-agent] 中断流式: convId={}, interrupted={}, hasState={}",
                conversationId, interrupted, hasState);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stopped", interrupted);
        data.put("hasInterruptState", hasState);
        return R.ok(data);
    }

    /**
     * 新消息顶掉进行中的回答：打断旧执行，等旧流真正停下后删除打断产生的快照
     * （断点已被新请求取代，半截回答保留在历史时间线里），随后走全新/恢复执行。
     * 极端情况旧执行没在超时内停下，返回 409 拒绝，防止同会话并行执行。
     */
    private void takeOverActiveStream(String convId) {
        boolean interrupted = dodoAgent.interrupt(convId, "新消息顶掉旧回答");
        // 等待旧流真正停止（快照落库、注册表摘除），最多 3 秒
        for (int i = 0; i < TAKEOVER_POLL_TIMES && liveStreams.isActive(convId); i++) {
            try {
                Thread.sleep(TAKEOVER_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (liveStreams.isActive(convId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话回答尚未停止，请稍后重试");
        }
        dodoAgent.discardInterruptedState(convId);
        log.info("[data-agent] 新消息顶掉进行中的回答: convId={}, interrupted={}", convId, interrupted);
    }

    /**
     * 恢复请求的前置校验：流存在、且当前用户是属主。
     */
    private void requireActiveOwned(String convId, Long userId) {
        if (!liveStreams.isActive(convId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前会话没有进行中的回答");
        }
        Long owner = liveStreams.ownerOf(convId);
        if (owner != null && !owner.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该会话的流");
        }
    }

    /** 联网开关（M6）：请求未带时默认 false */
    private static boolean isOnline(ChatRequest request) {
        return Boolean.TRUE.equals(request.online());
    }

    private ServerSentEvent<String> toSse(ServerSentEvent<AgentStreamEvent> sse) {
        AgentStreamEvent event = sse.data();
        try {
            ServerSentEvent.Builder<String> builder = ServerSentEvent
                    .builder(objectMapper.writeValueAsString(event))
                    .event(event.getType().name().toLowerCase());
            if (sse.id() != null) {
                builder.id(sse.id());
            }
            return builder.build();
        } catch (Exception e) {
            return ServerSentEvent.builder("{\"type\":\"ERROR\",\"message\":\"事件序列化失败\"}")
                    .event("error")
                    .build();
        }
    }

    private static String safeMessage(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
