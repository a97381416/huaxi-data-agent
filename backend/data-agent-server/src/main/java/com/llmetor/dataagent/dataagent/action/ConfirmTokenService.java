package com.llmetor.dataagent.dataagent.action;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 确认令牌服务（M-E0，协议见计划附录 B）：高危写操作两段式确认。
 *
 * <ul>
 *   <li>签发：首轮调用（无令牌）只返回「执行计划 + confirmToken」，不产生写副作用</li>
 *   <li>绑定：token 绑定 userId + action + payload 哈希——用户确认后 LLM 改参数 →
 *       哈希不匹配 → 令牌作废重出计划（防参数偷换）</li>
 *   <li>一次性：消费即失效（防 resubmit 类不幂等操作重复推进流程）</li>
 *   <li>TTL：默认 5 分钟过期</li>
 *   <li>作废：用户点[取消]即时 revoke（防过期窗口内被复用）</li>
 * </ul>
 *
 * <p>存储：进程内 ConcurrentHashMap（单实例够用）；多实例部署时迁 Redis
 * （key 前缀 huaxi-data-agent:，见计划附录 B 备注）。
 *
 * <p>安全根基：令牌只能由本服务签发（SecureRandom），LLM 无法构造——
 * 配合系统提示词「写操作红线」（严禁自行构造令牌）双保险。
 */
@Slf4j
@Component
public class ConfirmTokenService {

    /** 令牌生命周期校验结果（枚举名即话术素材，工具层翻译成人话） */
    public enum ConsumeResult {
        /** 校验通过，已消费（一次性生效） */
        OK,
        /** 令牌不存在（从未签发/已被取代） */
        NOT_FOUND,
        /** 已过期（超 TTL） */
        EXPIRED,
        /** 已使用过（一次性被违反，典型：重复提交） */
        ALREADY_USED,
        /** 用户不匹配（令牌签给别的 userId） */
        USER_MISMATCH,
        /** 动作不匹配（同一令牌用于别的高危动作） */
        ACTION_MISMATCH,
        /** payload 哈希不匹配（参数被篡改/现状漂移） */
        PAYLOAD_MISMATCH
    }

    /** 签发结果：令牌 + 有效期（渲染进执行计划卡片） */
    public record IssuedToken(String confirmToken, long expiresAtEpochMillis, long expiresInSeconds) {
    }

    /** 存储条目：used 的翻转只在 compute 里做，保证一次性语义原子 */
    private record TokenEntry(long userId, String action, String payloadHash,
                              long expiresAtMillis, boolean used) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TOKEN_PREFIX = "ct_";

    private final Map<String, TokenEntry> store = new ConcurrentHashMap<>();

    private final long ttlMillis;

    /** payload 哈希序列化器：键排序保证同内容同哈希（Map 键序不影响判定） */
    private final ObjectMapper canonicalMapper = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Autowired
    public ConfirmTokenService(
            @Value("${data-agent.action.confirm-token-ttl-seconds:300}") long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000;
    }

    /**
     * 签发确认令牌（首轮：生成执行计划时调用）。
     *
     * @param userId      归属用户（消费时必须一致）
     * @param action      动作名（如 resubmitLedger；消费时必须一致）
     * @param payloadHash {@link #payloadHash(Object)} 计算的绑定哈希（消费时必须一致）
     */
    public IssuedToken issue(long userId, String action, String payloadHash) {
        sweepExpired();
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        String token = TOKEN_PREFIX + HexFormat.of().formatHex(random);
        long expiresAt = System.currentTimeMillis() + ttlMillis;
        store.put(token, new TokenEntry(userId, action, payloadHash, expiresAt, false));
        log.info("[action] 签发确认令牌: userId={}, action={}, expiresInSeconds={}",
                userId, action, ttlMillis / 1000);
        return new IssuedToken(token, expiresAt, ttlMillis / 1000);
    }

    /**
     * 校验并消费令牌（二轮：携带令牌执行前调用）。
     * 校验通过当场标记 used（一次性）；任一不符返回具体原因，不消费。
     */
    public ConsumeResult verifyAndConsume(String confirmToken, long userId, String action, String payloadHash) {
        if (confirmToken == null || confirmToken.isBlank()) {
            return ConsumeResult.NOT_FOUND;
        }
        long now = System.currentTimeMillis();
        ConsumeResult[] out = new ConsumeResult[1];
        store.computeIfPresent(confirmToken, (k, entry) -> {
            if (entry.used()) {
                out[0] = ConsumeResult.ALREADY_USED;
                return entry;
            }
            if (now >= entry.expiresAtMillis()) {
                out[0] = ConsumeResult.EXPIRED;
                return null; // 顺手清理过期条目
            }
            if (entry.userId() != userId) {
                out[0] = ConsumeResult.USER_MISMATCH;
                return entry;
            }
            if (!entry.action().equals(action)) {
                out[0] = ConsumeResult.ACTION_MISMATCH;
                return entry;
            }
            if (!entry.payloadHash().equals(payloadHash)) {
                out[0] = ConsumeResult.PAYLOAD_MISMATCH;
                return entry; // 保留条目便于排查，但同 token 已不可能 OK（哈希不同下次仍不匹配）
            }
            out[0] = ConsumeResult.OK;
            return new TokenEntry(entry.userId(), entry.action(), entry.payloadHash(),
                    entry.expiresAtMillis(), true); // 消费：一次性
        });
        if (out[0] == null) {
            return ConsumeResult.NOT_FOUND;
        }
        log.info("[action] 令牌校验: userId={}, action={}, result={}", userId, action, out[0]);
        return out[0];
    }

    /** 用户主动取消：即时作废（防过期窗口内被复用）；返回是否存在过该令牌 */
    public boolean revoke(String confirmToken) {
        if (confirmToken == null || confirmToken.isBlank()) {
            return false;
        }
        boolean existed = store.remove(confirmToken) != null;
        if (existed) {
            log.info("[action] 令牌作废（用户取消）: token={}…", confirmToken.substring(0, Math.min(6, confirmToken.length())));
        }
        return existed;
    }

    /**
     * payload 规范化哈希：SHA-256(canonical JSON)。键排序 + 紧凑输出，
     * 同一业务内容不同 Map 键序得到相同哈希。
     */
    public String payloadHash(Object payload) {
        try {
            String canonical = canonicalMapper.writeValueAsString(payload);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // 哈希失败不可静默放行：用随机值保证必然 PAYLOAD_MISMATCH（fail-closed）
            log.warn("[action] payload 哈希计算失败，fail-closed: {}", e.getMessage());
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            return "invalid:" + HexFormat.of().formatHex(random);
        }
    }

    /** 清理已过期条目（签发时顺带执行，避免内存缓慢膨胀） */
    private void sweepExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now >= e.getValue().expiresAtMillis());
    }
}
