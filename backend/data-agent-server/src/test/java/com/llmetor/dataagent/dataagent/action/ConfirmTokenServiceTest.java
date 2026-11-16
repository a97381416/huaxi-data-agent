package com.llmetor.dataagent.dataagent.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认令牌生命周期单测（M-E0）：签发→校验消费全链 + 全部拒绝分支。
 * 覆盖：正常消费 / 一次性（复用拒）/ 过期 / 参数篡改（payload 哈希不匹配）/
 * 用户不匹配 / 动作不匹配 / 取消作废 / 未知令牌 / 哈希键序无关性。
 */
class ConfirmTokenServiceTest {

    private static final long USER = 1001L;
    private static final long OTHER_USER = 2002L;
    private static final String ACTION = "resubmitLedger";

    private ConfirmTokenService service;

    @BeforeEach
    void setUp() {
        service = new ConfirmTokenService(300); // 5 分钟 TTL（生产默认）
    }

    @Test
    void issueThenConsumeOk() {
        String hash = service.payloadHash(Map.of("ledgerId", "123", "patches", "[]"));
        ConfirmTokenService.IssuedToken issued = service.issue(USER, ACTION, hash);

        assertThat(issued.confirmToken()).startsWith("ct_");
        assertThat(issued.expiresInSeconds()).isEqualTo(300);

        ConfirmTokenService.ConsumeResult result = service.verifyAndConsume(
                issued.confirmToken(), USER, ACTION, hash);
        assertThat(result).isEqualTo(ConfirmTokenService.ConsumeResult.OK);
    }

    @Test
    void oneTimeConsumptionRejectedOnReuse() {
        String hash = service.payloadHash("payload");
        ConfirmTokenService.IssuedToken issued = service.issue(USER, ACTION, hash);

        assertThat(service.verifyAndConsume(issued.confirmToken(), USER, ACTION, hash))
                .isEqualTo(ConfirmTokenService.ConsumeResult.OK);
        // 第二次：同一令牌同一参数（典型：重复提交）必须拒绝
        assertThat(service.verifyAndConsume(issued.confirmToken(), USER, ACTION, hash))
                .isEqualTo(ConfirmTokenService.ConsumeResult.ALREADY_USED);
    }

    @Test
    void expiredTokenRejected() throws InterruptedException {
        ConfirmTokenService shortTtl = new ConfirmTokenService(0); // 立即过期
        String hash = service.payloadHash("payload");
        ConfirmTokenService.IssuedToken issued = shortTtl.issue(USER, ACTION, hash);
        Thread.sleep(10); // 确保越过过期时刻

        assertThat(shortTtl.verifyAndConsume(issued.confirmToken(), USER, ACTION, hash))
                .isEqualTo(ConfirmTokenService.ConsumeResult.EXPIRED);
    }

    @Test
    void tamperedPayloadRejected() {
        // 场景：用户确认后 LLM 偷改参数（payload 变了，令牌绑定的哈希对不上）
        String originalHash = service.payloadHash(Map.of("notApplicable", "-1"));
        ConfirmTokenService.IssuedToken issued = service.issue(USER, ACTION, originalHash);

        String tamperedHash = service.payloadHash(Map.of("notApplicable", "2"));
        assertThat(service.verifyAndConsume(issued.confirmToken(), USER, ACTION, tamperedHash))
                .isEqualTo(ConfirmTokenService.ConsumeResult.PAYLOAD_MISMATCH);
    }

    @Test
    void userMismatchRejected() {
        String hash = service.payloadHash("payload");
        ConfirmTokenService.IssuedToken issued = service.issue(USER, ACTION, hash);

        assertThat(service.verifyAndConsume(issued.confirmToken(), OTHER_USER, ACTION, hash))
                .isEqualTo(ConfirmTokenService.ConsumeResult.USER_MISMATCH);
    }

    @Test
    void actionMismatchRejected() {
        String hash = service.payloadHash("payload");
        ConfirmTokenService.IssuedToken issued = service.issue(USER, ACTION, hash);

        assertThat(service.verifyAndConsume(issued.confirmToken(), USER, "deleteProject", hash))
                .isEqualTo(ConfirmTokenService.ConsumeResult.ACTION_MISMATCH);
    }

    @Test
    void revokedTokenRejected() {
        String hash = service.payloadHash("payload");
        ConfirmTokenService.IssuedToken issued = service.issue(USER, ACTION, hash);

        assertThat(service.revoke(issued.confirmToken())).isTrue();
        assertThat(service.verifyAndConsume(issued.confirmToken(), USER, ACTION, hash))
                .isEqualTo(ConfirmTokenService.ConsumeResult.NOT_FOUND);
    }

    @Test
    void unknownOrBlankTokenRejected() {
        assertThat(service.verifyAndConsume("ct_not_exists", USER, ACTION, "hash"))
                .isEqualTo(ConfirmTokenService.ConsumeResult.NOT_FOUND);
        assertThat(service.verifyAndConsume(null, USER, ACTION, "hash"))
                .isEqualTo(ConfirmTokenService.ConsumeResult.NOT_FOUND);
        assertThat(service.verifyAndConsume("  ", USER, ACTION, "hash"))
                .isEqualTo(ConfirmTokenService.ConsumeResult.NOT_FOUND);
    }

    @Test
    void payloadHashIgnoresMapKeyOrder() {
        // 同内容不同键序必须同哈希（否则合法二调会被误判为参数篡改）
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("ledgerId", "L1");
        m1.put("patch", "p1");
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("patch", "p1");
        m2.put("ledgerId", "L1");

        assertThat(service.payloadHash(m1)).isEqualTo(service.payloadHash(m2));
        assertThat(service.payloadHash(m1)).isNotEqualTo(service.payloadHash(m2.get("ledgerId")));
    }

    @Test
    void tokensAreUniquePerIssue() {
        String hash = service.payloadHash("payload");
        String t1 = service.issue(USER, ACTION, hash).confirmToken();
        String t2 = service.issue(USER, ACTION, hash).confirmToken();
        assertThat(t1).isNotEqualTo(t2);
    }
}
