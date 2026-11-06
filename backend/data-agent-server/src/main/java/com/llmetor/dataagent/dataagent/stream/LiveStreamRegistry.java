package com.llmetor.dataagent.dataagent.stream;

import com.llmetor.miniagentx.model.AgentStreamEvent;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.Set;

/**
 * 进行中的流式回答注册表（key = conversationId，M3 执行与连接解耦的核心）。
 *
 * <p>两个职责（对齐原版 LiveStreamRegistry，只做 standalone 内存版；Redis Stream
 * 多实例版属 M9 可选增强）：
 * <ul>
 *   <li><b>执行与连接解耦</b>：Agent 流注册后立即后台驱动（ConnectableFlux.connect），
 *       前端断开（关页/刷新/断网）只取消前端自己的订阅，执行照常完成并落库</li>
 *   <li><b>断点续传</b>：每个事件带自增序号（SSE id 字段，从 1 起），前端重连时带
 *       startSeq，先瞬时补发序号 ≥ startSeq 的缓存事件，再接实时尾流</li>
 * </ul>
 *
 * <p>流终止（完成/出错/被打断暂停）时从注册表摘除，可被 GC。
 */
public interface LiveStreamRegistry {

    /**
     * 注册并立即后台驱动。
     *
     * @param conversationId 会话 ID
     * @param ownerUserId    属主用户（恢复时校验权限）
     * @param source         Agent 事件源流
     * @return 可重放的 SSE 流（事件已带序号）
     */
    Flux<ServerSentEvent<AgentStreamEvent>> register(String conversationId, Long ownerUserId,
                                                     Flux<AgentStreamEvent> source);

    /**
     * 接回进行中的流：先补发序号 >= startSeq 的缓存事件，再续实时尾流。
     *
     * @return 没有进行中的流时返回 null
     */
    Flux<ServerSentEvent<AgentStreamEvent>> attach(String conversationId, long startSeq);

    /** 指定会话是否有进行中的流 */
    boolean isActive(String conversationId);

    /**
     * 全部进行中流的会话 ID（列表接口批量标记 streaming 用，一次查询避免逐会话往返）。
     */
    Set<String> activeConversationIds();

    /** 进行中流的属主用户（无进行中流时为 null） */
    Long ownerOf(String conversationId);
}
