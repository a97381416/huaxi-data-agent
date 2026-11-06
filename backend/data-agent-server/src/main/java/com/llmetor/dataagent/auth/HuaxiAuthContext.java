package com.llmetor.dataagent.auth;

/**
 * 华西登录态上下文（M-H2）：HuaxiAuthFilter 解析 token 后放入，
 * 各服务经 {@link #getCurrentUserIdOrNull()} 消费（替代 Sa-Token 的 StpUtil）。
 *
 * <p>ThreadLocal 生命周期由 Filter 的 finally 清理保证。
 */
public final class HuaxiAuthContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private HuaxiAuthContext() {
    }

    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getCurrentUserIdOrNull() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
