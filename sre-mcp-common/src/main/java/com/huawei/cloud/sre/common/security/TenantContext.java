package com.huawei.cloud.sre.common.security;

/**
 * 多租户上下文，基于 ThreadLocal 存储当前请求的租户信息。
 *
 * <p>虚拟线程（Virtual Thread）独立持有 ThreadLocal，因此在 Spring Boot 3.3 + Virtual Threads 环境下
 * 此实现是线程安全的，无需额外适配。
 *
 * <p>上下文由 Security Filter 在请求入口处设置，在请求结束时必须调用 {@link #clear()} 防止内存泄漏。
 */
public final class TenantContext {

    private TenantContext() {}

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> SCOPE = new ThreadLocal<>();

    /**
     * 设置当前请求的租户上下文。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID（来自 JWT sub）
     * @param scope    OAuth 2.1 scope（如 "read write"）
     */
    public static void set(String tenantId, String userId, String scope) {
        TENANT_ID.set(tenantId);
        USER_ID.set(userId);
        SCOPE.set(scope);
    }

    /**
     * 获取当前租户 ID。
     *
     * @return 当前租户 ID，若未设置则返回 "default"
     */
    public static String getTenantId() {
        String id = TENANT_ID.get();
        return id != null ? id : "default";
    }

    /**
     * 获取当前用户 ID。
     *
     * @return 当前用户 ID，若未设置则返回 "anonymous"
     */
    public static String getUserId() {
        String id = USER_ID.get();
        return id != null ? id : "anonymous";
    }

    /**
     * 获取当前 OAuth 2.1 scope。
     *
     * @return scope 字符串，若未设置则返回空字符串
     */
    public static String getScope() {
        String s = SCOPE.get();
        return s != null ? s : "";
    }

    /**
     * 判断当前上下文是否具备指定 scope。
     *
     * @param requiredScope 所需的 scope
     * @return true 表示有权限
     */
    public static boolean hasScope(String requiredScope) {
        String currentScope = getScope();
        if (currentScope.isBlank()) {
            return false;
        }
        for (String s : currentScope.split("\\s+")) {
            if (s.equals(requiredScope)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清除当前线程的租户上下文，防止内存泄漏。
     *
     * <p>必须在请求结束（Filter finally 块）中调用。
     */
    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
        SCOPE.remove();
    }
}
