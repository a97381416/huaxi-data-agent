package com.llmetor.dataagent.dataagent.stream;

import com.llmetor.miniagentx.model.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进行中的流式回答注册表——内存版（standalone 单机部署）。
 *
 * <p>执行与连接解耦：Agent 流注册后立即后台驱动（connect），HTTP 断开只取消
 * 前端自己的订阅，Agent 照常执行、事件照常进 replay 缓存；断点续传：每个事件
 * 带自增序号（SSE id，从 1 起），前端重连带 startSeq，先瞬时补发错过的事件，
 * 再接实时尾流。流终止时按 key+value 摘除（同一会话紧接着开下一轮时不会误删新流）。
 */
@Slf4j
@Component
public class InMemoryLiveStreamRegistry implements LiveStreamRegistry {

    /** 一条进行中的流：可重放的 SSE 流（事件已带序号）+ 属主用户（恢复时校验权限） */
    private record LiveStream(ConnectableFlux<ServerSentEvent<AgentStreamEvent>> replay,
                              Long ownerUserId) {
    }

    private final Map<String, LiveStream> live = new ConcurrentHashMap<>();

    @Override
    public Flux<ServerSentEvent<AgentStreamEvent>> register(String conversationId, Long ownerUserId,
                                                            Flux<AgentStreamEvent> source) {
        AtomicReference<LiveStream> self = new AtomicReference<>();
        ConnectableFlux<ServerSentEvent<AgentStreamEvent>> replay = source
                // 事件序号写进 SSE id 字段：前端按 id 记录游标，重连带 startSeq 增量续传
                .index((i, evt) -> ServerSentEvent.builder(evt).id(String.valueOf(i + 1)).build())
                .doFinally(signal -> {
                    LiveStream entry = self.get();
                    if (entry != null) {
                        // 按 key+value 摘除：同一会话紧接着开下一轮时，不会误删新注册的流
                        live.remove(conversationId, entry);
                        log.info("[data-agent] 流式回答结束摘除: convId={}, signal={}", conversationId, signal);
                    }
                })
                .replay();
        LiveStream entry = new LiveStream(replay, ownerUserId);
        self.set(entry);
        live.put(conversationId, entry);
        replay.connect();
        return replay;
    }

    @Override
    public Flux<ServerSentEvent<AgentStreamEvent>> attach(String conversationId, long startSeq) {
        LiveStream entry = live.get(conversationId);
        if (entry == null) {
            return null;
        }
        long from = Math.max(startSeq, 1);
        return entry.replay().skipUntil(sse -> sse.id() != null && Long.parseLong(sse.id()) >= from);
    }

    @Override
    public boolean isActive(String conversationId) {
        return live.containsKey(conversationId);
    }

    @Override
    public Set<String> activeConversationIds() {
        return Set.copyOf(live.keySet());
    }

    @Override
    public Long ownerOf(String conversationId) {
        LiveStream entry = live.get(conversationId);
        return entry == null ? null : entry.ownerUserId();
    }
}
