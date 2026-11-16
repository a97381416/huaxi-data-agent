package com.llmetor.dataagent.dataagent.action.fill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 填报节点会话选择缓存（节点确认门槛）：conversationId → 当前确认的填报节点。
 *
 * <p>{@code listFillNodes} 确认节点时写入；暂存/提审链路用于校验当前会话聚焦的填报节点；
 * 提审成功即删。{@link #get(String, String, String)} 带项目/子阶段匹配校验——会话中
 * 切换项目/阶段后旧选择自然失效。
 *
 * <p>进程内存储（单实例），TTL 可配（{@code data-agent.fill.selection-ttl-seconds}，默认 2h）。
 */
@Slf4j
@Component
public class FillNodeSelectionCache {

    /** 节点选择快照：定位上下文 + 确认的节点 */
    public record Selection(String projectId, String phaseId, String nodeId, String nodeName,
                            long expiresAtMillis) {
    }

    private final Map<String, Selection> store = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public FillNodeSelectionCache(
            @Value("${data-agent.fill.selection-ttl-seconds:7200}") long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000;
    }

    /** 记录/切换当前会话确认的填报节点（过期时间在此统一计算） */
    public void put(String conversationId, String projectId, String phaseId, String nodeId, String nodeName) {
        if (conversationId == null || conversationId.isBlank()
                || nodeId == null || nodeId.isBlank()) {
            return;
        }
        sweepExpired();
        store.put(conversationId, new Selection(projectId == null ? "" : projectId,
                phaseId == null ? "" : phaseId, nodeId, nodeName == null ? "" : nodeName,
                System.currentTimeMillis() + ttlMillis));
    }

    /** 取当前选择（须与项目/子阶段匹配才算数；过期视同不存在） */
    public Selection get(String conversationId, String projectId, String phaseId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        Selection s = store.get(conversationId);
        if (s == null) {
            return null;
        }
        if (System.currentTimeMillis() >= s.expiresAtMillis()) {
            store.remove(conversationId);
            return null;
        }
        return s.projectId().equals(projectId == null ? "" : projectId)
                && s.phaseId().equals(phaseId == null ? "" : phaseId) ? s : null;
    }

    /** 提审成功/会话作废时清除 */
    public Selection remove(String conversationId) {
        return conversationId == null ? null : store.remove(conversationId);
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now >= e.getValue().expiresAtMillis());
    }
}
