package com.huawei.cloud.sre.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * MCP Tool 自动埋点切面。
 *
 * <p>拦截所有标注了 {@code @Tool} 的方法，自动记录：
 * <ul>
 *   <li>{@code mcp_tool_calls_total}：调用总次数（含 status=success/error 标签）</li>
 *   <li>{@code mcp_tool_duration_seconds}：执行耗时分布</li>
 * </ul>
 *
 * <p>这些指标通过 {@code /actuator/prometheus} 暴露，可接入华为云 AOM 或 Grafana。
 */
@Aspect
@Component
public class ToolMetricsAspect {

    private static final Logger log = LoggerFactory.getLogger(ToolMetricsAspect.class);

    private final MeterRegistry meterRegistry;

    /**
     * @param meterRegistry Micrometer 指标注册表
     */
    public ToolMetricsAspect(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 环绕通知：对所有 @Tool 方法自动埋点。
     *
     * @param joinPoint 连接点
     * @return 方法执行结果
     * @throws Throwable 方法执行异常透传
     */
    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object recordMetrics(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Tool toolAnnotation = method.getAnnotation(Tool.class);
        String toolName = method.getName();

        log.info("Tool[{}] invoked", toolName);
        Timer.Sample sample = Timer.start(meterRegistry);
        String status = "success";
        try {
            Object result = joinPoint.proceed();
            log.info("Tool[{}] completed", toolName);
            return result;
        } catch (Throwable t) {
            status = "error";
            log.error("Tool[{}] failed: {}", toolName, t.getMessage());
            throw t;
        } finally {
            String finalStatus = status;
            sample.stop(meterRegistry.timer("mcp_tool_duration_seconds",
                    "tool", toolName, "status", finalStatus));
            Counter.builder("mcp_tool_calls_total")
                    .tag("tool", toolName)
                    .tag("status", finalStatus)
                    .register(meterRegistry)
                    .increment();
        }
    }
}
