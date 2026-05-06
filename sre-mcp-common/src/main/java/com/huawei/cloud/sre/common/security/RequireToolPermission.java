package com.huawei.cloud.sre.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明 MCP Tool 方法所需的权限 scope。
 *
 * <p>标注在 {@code @Tool} 方法上，由 {@link ToolPermissionAspect} 在方法执行前检查当前用户是否拥有所需 scope。
 * 若权限不足，抛出 {@link com.huawei.cloud.sre.common.exception.McpToolException}（code=PERMISSION_DENIED）。
 *
 * <p>示例：
 * <pre>{@code
 * @Tool(description = "重启 Pod")
 * @RequireToolPermission(scope = "remediation:write")
 * public RemediationResult restartPod(String podName) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireToolPermission {

    /**
     * 所需的 OAuth 2.1 scope，如 {@code "rca:read"}、{@code "remediation:write"}。
     *
     * @return scope 字符串
     */
    String scope();

    /**
     * 权限检查失败时的提示信息（可选，用于错误日志）。
     *
     * @return 自定义错误消息
     */
    String message() default "";
}
