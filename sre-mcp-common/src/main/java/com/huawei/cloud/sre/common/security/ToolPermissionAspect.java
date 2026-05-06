package com.huawei.cloud.sre.common.security;

import com.huawei.cloud.sre.common.exception.McpToolException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Tool 权限校验切面。
 *
 * <p>拦截标注了 {@link RequireToolPermission} 的方法，从 {@link TenantContext} 读取当前用户的
 * OAuth 2.1 scope，校验是否满足方法声明的权限要求。
 */
@Aspect
@Component
public class ToolPermissionAspect {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionAspect.class);

    /**
     * 环绕通知：在方法执行前校验权限。
     *
     * @param joinPoint          连接点
     * @param requirePermission  权限注解实例
     * @return 方法执行结果
     * @throws Throwable 若权限不足或方法本身抛出异常
     */
    @Around("@annotation(requirePermission)")
    public Object checkPermission(ProceedingJoinPoint joinPoint, RequireToolPermission requirePermission) throws Throwable {
        String requiredScope = requirePermission.scope();
        String userId = TenantContext.getUserId();
        String tenantId = TenantContext.getTenantId();

        if (!TenantContext.hasScope(requiredScope)) {
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            String message = requirePermission.message().isBlank()
                    ? String.format("用户 [%s] 缺少权限 [%s]，无法调用 Tool [%s]", userId, requiredScope, method.getName())
                    : requirePermission.message();
            log.warn("Permission denied userId={} tenantId={} requiredScope={} tool={}",
                    userId, tenantId, requiredScope, method.getName());
            throw McpToolException.permissionDenied(message);
        }

        log.debug("Permission granted userId={} tenantId={} scope={}", userId, tenantId, requiredScope);
        return joinPoint.proceed();
    }
}
