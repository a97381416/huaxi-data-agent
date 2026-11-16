package com.llmetor.dataagent.dataagent.action.fill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提审执行计划缓存（M-E2）：确认令牌 → 提审计划快照。
 *
 * <p>W2 两段式的第二轮（用户确认后）只需 ledgerId + confirmToken，提审配置
 * （审核人/提交说明/节点策略）从缓存取——避免 LLM 复述失真；令牌的 payload 哈希绑定
 * 「重拉草稿后重新预检的生效内容 + 提审配置」，草稿漂移/参数偷换 → 哈希不匹配 →
 * 令牌作废重出卡（与 M-E1 ActionPlanCache 同构）。
 *
 * <p>生命周期与 {@link com.llmetor.dataagent.dataagent.action.ConfirmTokenService} 对齐：
 * TTL 同配置（默认 300s），消费/取消/过期即删；进程内存储（单实例）。
 */
@Slf4j
@Component
public class SubmitPlanCache {

    /** 提审计划快照：定位上下文 + 提审配置 */
    public record Plan(String ledgerId, String projectId, String phaseId,
                       String submitRemark, List<String> assigneeIds, List<String> assigneeNames,
                       String deptName, long expiresAtMillis) {
    }

    private final Map<String, Plan> store = new ConcurrentHashMap<>();
    private final long ttlMillis;

    @Autowired
    public SubmitPlanCache(
            @Value("${data-agent.action.confirm-token-ttl-seconds:300}") long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000;
    }

    public void put(String confirmToken, Plan plan) {
        sweepExpired();
        store.put(confirmToken, plan);
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
        return confirmToken == null ? null : store.remove(confirmToken);
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now >= e.getValue().expiresAtMillis());
    }
}
