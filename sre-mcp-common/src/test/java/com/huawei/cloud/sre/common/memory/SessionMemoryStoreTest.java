package com.huawei.cloud.sre.common.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
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
class SessionMemoryStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private SessionMemoryStore store;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        store = new SessionMemoryStore(redisTemplate);
    }

    @Test
    void put_callsRedisWithCorrectKeyAndTtl() {
        store.put("session-1", "incidentId", "INC-001");
        verify(valueOps).set(
                eq("mcp:session:session-1:incidentId"),
                eq("INC-001"),
                any(Duration.class)
        );
    }

    @Test
    void put_withCustomTtl_usesThatTtl() {
        Duration customTtl = Duration.ofMinutes(30);
        store.put("session-1", "field", "value", customTtl);
        verify(valueOps).set("mcp:session:session-1:field", "value", customTtl);
    }

    @Test
    void get_whenValueExists_returnsValue() {
        when(valueOps.get("mcp:session:session-1:incidentId")).thenReturn("INC-001");
        Optional<String> result = store.get("session-1", "incidentId");
        assertThat(result).isPresent().contains("INC-001");
    }

    @Test
    void get_whenValueAbsent_returnsEmpty() {
        when(valueOps.get("mcp:session:session-1:missing")).thenReturn(null);
        Optional<String> result = store.get("session-1", "missing");
        assertThat(result).isEmpty();
    }

    @Test
    void delete_callsRedisDelete() {
        store.delete("session-1", "incidentId");
        verify(redisTemplate).delete("mcp:session:session-1:incidentId");
    }

    @Test
    void exists_whenKeyPresent_returnsTrue() {
        when(redisTemplate.hasKey("mcp:session:session-1:field")).thenReturn(Boolean.TRUE);
        assertThat(store.exists("session-1", "field")).isTrue();
    }

    @Test
    void exists_whenKeyAbsent_returnsFalse() {
        when(redisTemplate.hasKey("mcp:session:session-1:field")).thenReturn(Boolean.FALSE);
        assertThat(store.exists("session-1", "field")).isFalse();
    }
}
