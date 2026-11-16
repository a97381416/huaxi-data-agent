package com.llmetor.dataagent.dataagent.action.task;

import com.llmetor.dataagent.dataagent.action.ConfirmTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高危动作执行计划缓存（M-E1）：确认令牌 → 计划快照。
 *
 * <p>W2 两段式的第二轮（用户确认后）**只需 taskId + confirmToken**，patch 批从缓存取——
 * 避免 LLM 复述 patch 失真；令牌的 payload 哈希绑定「重拉现状后重新解析的结果」，
 * 现状漂移/参数偷换 → 哈希不匹配 → 令牌作废重出卡（D5）。
 *
 * <p>生命周期与 {@link ConfirmTokenService} 对齐：TTL 同配置（默认 300s），
 * 消费/取消/过期即删；进程内存储（单实例，多实例部署随令牌一起迁 Redis）。
 */
@Slf4j
@Component
public class ActionPlanCache {

    /**
     * 计划快照：定位上下文 + 原 patch 批（二轮重拉现状重新解析用）+ 重提审核人。
     * assigneeIds 仅 resubmitLedger 使用（重提同提审一样需指定新一轮审核人，
     * 主平台引擎 needNext=true 且 nextNodes 非空才创建待办）；其余动作为 null。
     */
    public record Plan(String taskId, String ledgerId, List<PatchMerger.PatchSpec> patches,
                       List<String> assigneeIds, long expiresAtMillis) {
    }

    private final Map<String, Plan> store = new ConcurrentHashMap<>();
    /** taskId → 该任务当前有效的计划令牌集（换人/重出计划时作废同任务旧计划，防拿旧审核人提交） */
    private final Map<String, Set<String>> tokensByTask = new ConcurrentHashMap<>();
    private final long ttlMillis;

    @Autowired
    public ActionPlanCache(
            @Value("${data-agent.action.confirm-token-ttl-seconds:300}") long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000;
    }

    public void put(String confirmToken, String taskId, String ledgerId,
                    List<PatchMerger.PatchSpec> patches) {
        put(confirmToken, taskId, ledgerId, patches, null);
    }

    public void put(String confirmToken, String taskId, String ledgerId,
                    List<PatchMerger.PatchSpec> patches, List<String> assigneeIds) {
        sweepExpired();
        invalidateOtherPlansForTask(taskId, confirmToken);
        store.put(confirmToken, new Plan(taskId, ledgerId, List.copyOf(patches),
                assigneeIds == null ? null : List.copyOf(assigneeIds),
                System.currentTimeMillis() + ttlMillis));
        tokensByTask.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(confirmToken);
    }

    /** 同任务重出计划（换审核人/内容变更）时作废旧计划：旧令牌二轮按"计划不存在"拒绝 */
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
