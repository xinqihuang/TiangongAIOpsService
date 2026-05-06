package com.huawei.cloud.sre.common.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdempotencyStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private IdempotencyStore store;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        store = new IdempotencyStore(redisTemplate);
    }

    @Test
    void tryAcquire_firstCall_returnsTrue() {
        when(valueOps.setIfAbsent(eq("mcp:idempotency:key-1:lock"), eq("1"), any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        assertThat(store.tryAcquire("key-1")).isTrue();
    }

    @Test
    void tryAcquire_duplicateCall_returnsFalse() {
        when(valueOps.setIfAbsent(eq("mcp:idempotency:key-1:lock"), eq("1"), any(Duration.class)))
                .thenReturn(Boolean.FALSE);
        assertThat(store.tryAcquire("key-1")).isFalse();
    }

    @Test
    void tryAcquire_withCustomTtl_usesThatTtl() {
        Duration ttl = Duration.ofMinutes(5);
        when(valueOps.setIfAbsent("mcp:idempotency:key-2:lock", "1", ttl)).thenReturn(Boolean.TRUE);
        assertThat(store.tryAcquire("key-2", ttl)).isTrue();
    }

    @Test
    void saveResult_storesResultWithKey() {
        store.saveResult("key-1", "{\"status\":\"ok\"}");
        verify(valueOps).set(
                eq("mcp:idempotency:key-1:result"),
                eq("{\"status\":\"ok\"}"),
                any(Duration.class)
        );
    }

    @Test
    void getResult_whenResultExists_returnsResult() {
        when(valueOps.get("mcp:idempotency:key-1:result")).thenReturn("{\"status\":\"ok\"}");
        Optional<String> result = store.getResult("key-1");
        assertThat(result).isPresent().contains("{\"status\":\"ok\"}");
    }

    @Test
    void getResult_whenResultAbsent_returnsEmpty() {
        when(valueOps.get("mcp:idempotency:key-1:result")).thenReturn(null);
        assertThat(store.getResult("key-1")).isEmpty();
    }

    @Test
    void release_deletesLockKey() {
        store.release("key-1");
        verify(redisTemplate).delete("mcp:idempotency:key-1:lock");
    }
}
