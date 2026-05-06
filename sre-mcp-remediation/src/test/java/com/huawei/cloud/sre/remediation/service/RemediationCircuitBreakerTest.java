package com.huawei.cloud.sre.remediation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemediationCircuitBreakerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RemediationCircuitBreaker circuitBreaker;

    private static final String SERVICE = "order-service";
    private static final String ACTION = "restartPod";

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        circuitBreaker = new RemediationCircuitBreaker(redisTemplate, 3, 30, 30);
    }

    @Test
    void isOpen_whenNoOpenKey_returnsFalse() {
        when(redisTemplate.hasKey("remediation:cb:open:order-service:restartPod")).thenReturn(false);
        assertThat(circuitBreaker.isOpen(SERVICE, ACTION)).isFalse();
    }

    @Test
    void isOpen_whenOpenKeyExists_returnsTrue() {
        when(redisTemplate.hasKey("remediation:cb:open:order-service:restartPod")).thenReturn(true);
        assertThat(circuitBreaker.isOpen(SERVICE, ACTION)).isTrue();
    }

    @Test
    void recordFailure_belowThreshold_doesNotOpen() {
        when(valueOps.increment("remediation:cb:failures:order-service:restartPod")).thenReturn(1L);

        circuitBreaker.recordFailure(SERVICE, ACTION);

        verify(valueOps, never()).set(eq("remediation:cb:open:order-service:restartPod"),
                anyString(), any(Duration.class));
    }

    @Test
    void recordFailure_firstFailure_setsExpiry() {
        when(valueOps.increment("remediation:cb:failures:order-service:restartPod")).thenReturn(1L);

        circuitBreaker.recordFailure(SERVICE, ACTION);

        verify(redisTemplate).expire(eq("remediation:cb:failures:order-service:restartPod"),
                eq(Duration.ofMinutes(30)));
    }

    @Test
    void recordFailure_atThreshold_opensCircuitBreaker() {
        when(valueOps.increment("remediation:cb:failures:order-service:restartPod")).thenReturn(3L);

        circuitBreaker.recordFailure(SERVICE, ACTION);

        verify(valueOps).set(eq("remediation:cb:open:order-service:restartPod"),
                anyString(), eq(Duration.ofMinutes(30)));
    }

    @Test
    void recordSuccess_deletesFailureKey() {
        circuitBreaker.recordSuccess(SERVICE, ACTION);

        verify(redisTemplate).delete("remediation:cb:failures:order-service:restartPod");
    }

    @Test
    void reset_deletesOpenAndFailureKeys() {
        circuitBreaker.reset(SERVICE, ACTION);

        verify(redisTemplate).delete("remediation:cb:open:order-service:restartPod");
        verify(redisTemplate).delete("remediation:cb:failures:order-service:restartPod");
    }

    @Test
    void getFailureCount_noKey_returnsZero() {
        when(valueOps.get("remediation:cb:failures:order-service:restartPod")).thenReturn(null);
        assertThat(circuitBreaker.getFailureCount(SERVICE, ACTION)).isZero();
    }

    @Test
    void getFailureCount_withValue_returnsCount() {
        when(valueOps.get("remediation:cb:failures:order-service:restartPod")).thenReturn("2");
        assertThat(circuitBreaker.getFailureCount(SERVICE, ACTION)).isEqualTo(2);
    }

    @Test
    void getFailureCount_invalidValue_returnsZero() {
        when(valueOps.get("remediation:cb:failures:order-service:restartPod")).thenReturn("not-a-number");
        assertThat(circuitBreaker.getFailureCount(SERVICE, ACTION)).isZero();
    }
}
