package com.llmetor.dataagent.dataagent.action.review;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 审核高危动作执行计划缓存（M-E3）：确认令牌 → 计划快照。
 *
 * <p>三个审核动作（approveTask / rejectTask / partialApproveLedger）共用：
 * 首轮把**最终请求体**（已解析 targetNodeId/entryId/assigneeIds 的完整 JSON）+ 动作名 +
 * payload 哈希存入快照；二轮只传 taskId + confirmToken，从快照取请求体原样提交——
 * 避免 LLM 复述参数失真。令牌的 payload 哈希绑定请求体规范化 JSON（键排序），
 * 二轮重算比对（参数偷换 → PAYLOAD_MISMATCH）；任务现状漂移由工具层重查任务状态兜底。
 *
 * <p>生命周期与 ConfirmTokenService 对齐：TTL 同配置（默认 300s），
 * 消费/取消/过期即删；进程内存储（单实例，多实例部署随令牌一起迁 Redis）。
 */
@Slf4j
@Component
public class ReviewPlanCache {

    /**
     * 计划快照：动作名 + 任务定位 + 最终请求体（ObjectNode，二轮原样 POST）+ 影响说明
     * （outcomeNote：二轮回报与重出计划时复用，避免 LLM 复述失真）。
     * requestBodyJson 为规范化序列化串（键排序），payload 哈希基于它计算保证稳定。
     */
    public record Plan(String action, String taskId, String ledgerId,
                       String requestBodyJson, JsonNode requestBody, String outcomeNote,
                       long expiresAtMillis) {
    }

    private final Map<String, Plan> store = new ConcurrentHashMap<>();
    /** taskId → 该任务当前有效的计划令牌集（重出计划时作废同任务旧计划，防拿旧参数提交） */
    private final Map<String, Set<String>> tokensByTask = new ConcurrentHashMap<>();
    private final long ttlMillis;

    @Autowired
    public ReviewPlanCache(
            @Value("${data-agent.action.confirm-token-ttl-seconds:300}") long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000;
    }

    public void put(String confirmToken, String action, String taskId, String ledgerId,
                    JsonNode requestBody, String outcomeNote) {
        sweepExpired();
        invalidateOtherPlansForTask(taskId, confirmToken);
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                    .writeValueAsString(requestBody);
        } catch (Exception e) {
            json = requestBody.toString();
        }
        store.put(confirmToken, new Plan(action, taskId, ledgerId, json,
                requestBody.deepCopy(), outcomeNote == null ? "" : outcomeNote,
                System.currentTimeMillis() + ttlMillis));
        tokensByTask.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(confirmToken);
    }

    /** 同任务重出计划（参数变更）时作废旧计划：旧令牌二轮按"计划不存在"拒绝 */
    private void invalidateOtherPlansForTask(String taskId, String keepToken) {
        Set<String> tokens = tokensByTask.get(taskId);
        if (tokens != null) {
            for (String t : tokens) {
                if (!t.equals(keepToken)) {
                    store.remove(t);
                }
            }
        }
    }

    /** 取计划（过期视同不存在）；不删除——消费随令牌 verifyAndConsume 成功后显式 remove */
    public Plan get(String confirmToken) {
        if (confirmToken == null || confirmToken.isBlank()) {
            return null;
        }
        Plan plan = store.get(confirmToken);
        if (plan == null) {
            return null;
        }
        if (System.currentTimeMillis() >= plan.expiresAtMillis()) {
            store.remove(confirmToken);
            return null;
        }
        return plan;
    }

    /** 计划随令牌一并作废（执行成功 / 用户取消 / 重新出卡） */
    public Plan remove(String confirmToken) {
        if (confirmToken == null) {
            return null;
        }
        Plan removed = store.remove(confirmToken);
        if (removed != null) {
            Set<String> tokens = tokensByTask.get(removed.taskId());
            if (tokens != null) {
                tokens.remove(confirmToken);
            }
        }
        return removed;
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now >= e.getValue().expiresAtMillis());
    }
}
