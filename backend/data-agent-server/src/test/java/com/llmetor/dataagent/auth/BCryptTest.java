package com.llmetor.dataagent.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BCrypt 加密往返自测（不依赖 Spring 上下文与数据库）。
 * 种子数据密码哈希由同一算法生成，此测试保证登录校验算法与种子算法一致。
 */
class BCryptTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Test
    void encodeAndMatches() {
        String hash = ENCODER.encode("123456");
        assertTrue(ENCODER.matches("123456", hash), "正确密码应通过校验");
        assertFalse(ENCODER.matches("wrong", hash), "错误密码应拒绝");
        assertFalse(ENCODER.matches("", hash), "空密码应拒绝");
        assertTrue(hash.startsWith("$2"), "BCrypt 哈希格式应为 $2 前缀，实际: " + hash);
    }

    @Test
    void samePasswordDifferentSalt() {
        String h1 = ENCODER.encode("123456");
        String h2 = ENCODER.encode("123456");
        assertFalse(h1.equals(h2), "两次加密应产生不同哈希（随机盐）");
        assertTrue(ENCODER.matches("123456", h1) && ENCODER.matches("123456", h2));
    }
}
