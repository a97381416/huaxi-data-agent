package com.llmetor.dataagent.auth;

/**
 * 华西登录态上下文（M-H2）：HuaxiAuthFilter 解析 token 后放入，
 * 各服务经 {@link #getCurrentUserIdOrNull()} 消费（替代 Sa-Token 的 StpUtil）。
 *
 * <p>M-E0 扩展：除 userId 外携带 {@code tenantId}（REST 转发主后端需 tenant-id 头，
 * 取自 system_users / token 表）与原始 {@code token}（写通道以当前用户身份转发）。
 *
 * <p>ThreadLocal 生命周期由 Filter 的 finally 清理保证。
 */
public final class HuaxiAuthContext {

    /** 登录态三元组：userId + tenantId（M-E0）+ 原始 token（M-E0，写通道转发凭证） */
    public record AuthInfo(Long userId, Long tenantId, String token) {
    }

    private static final ThreadLocal<AuthInfo> CONTEXT = new ThreadLocal<>();

    private HuaxiAuthContext() {
    }

    public static void set(Long userId) {
        CONTEXT.set(new AuthInfo(userId, null, null));
    }

    /** M-E0：完整登录态（userId + tenantId + token） */
    public static void set(Long userId, Long tenantId, String token) {
        CONTEXT.set(new AuthInfo(userId, tenantId, token));
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static Long getCurrentUserIdOrNull() {
        AuthInfo info = CONTEXT.get();
        return info == null ? null : info.userId();
    }

    /** M-E0：当前用户租户 ID；未解析到登录态时 null */
    public static Long getTenantIdOrNull() {
        AuthInfo info = CONTEXT.get();
        return info == null ? null : info.tenantId();
    }

    /** M-E0：当前请求原始 Bearer token；未解析到登录态时 null */
    public static String getTokenOrNull() {
        AuthInfo info = CONTEXT.get();
        return info == null ? null : info.token();
    }

    /** M-E0：完整登录态；未登录 null */
    public static AuthInfo getOrNull() {
        return CONTEXT.get();
    }
}
