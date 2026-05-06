package com.huawei.cloud.sre.common.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 基于 Redis 的 MCP 会话状态存储。
 *
 * <p>存储跨 Tool 调用的会话上下文（如当前正在分析的 incident ID、已收集的证据列表），
 * 使 LLM 在多轮对话中保持状态连贯性。
 *
 * <p>Key 格式：{@code mcp:session:{sessionId}:{field}}
 */
@Component
public class SessionMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryStore.class);
    private static final String KEY_PREFIX = "mcp:session:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redisTemplate;

    /**
     * @param redisTemplate Spring Redis 模板（使用 Lettuce 连接池）
     */
    public SessionMemoryStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 存储会话字段值。
     *
     * @param sessionId 会话 ID（由 MCP Client 生成）
     * @param field     字段名，如 "incidentId"、"evidenceList"
     * @param value     字段值（JSON 字符串）
     */
    public void put(String sessionId, String field, String value) {
        String key = buildKey(sessionId, field);
        redisTemplate.opsForValue().set(key, value, DEFAULT_TTL);
        log.debug("SessionMemory put sessionId={} field={}", sessionId, field);
    }

    /**
     * 存储会话字段值，自定义 TTL。
     *
     * @param sessionId 会话 ID
     * @param field     字段名
     * @param value     字段值
     * @param ttl       生存时间
     */
    public void put(String sessionId, String field, String value, Duration ttl) {
        String key = buildKey(sessionId, field);
        redisTemplate.opsForValue().set(key, value, ttl);
        log.debug("SessionMemory put sessionId={} field={} ttl={}", sessionId, field, ttl);
    }

    /**
     * 读取会话字段值。
     *
     * @param sessionId 会话 ID
     * @param field     字段名
     * @return 字段值，若不存在则返回 empty
     */
    public Optional<String> get(String sessionId, String field) {
        String key = buildKey(sessionId, field);
        String value = redisTemplate.opsForValue().get(key);
        return Optional.ofNullable(value);
    }

    /**
     * 删除会话字段。
     *
     * @param sessionId 会话 ID
     * @param field     字段名
     */
    public void delete(String sessionId, String field) {
        String key = buildKey(sessionId, field);
        redisTemplate.delete(key);
        log.debug("SessionMemory delete sessionId={} field={}", sessionId, field);
    }

    /**
     * 判断会话字段是否存在。
     *
     * @param sessionId 会话 ID
     * @param field     字段名
     * @return true 表示字段存在且未过期
     */
    public boolean exists(String sessionId, String field) {
        String key = buildKey(sessionId, field);
        Boolean hasKey = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(hasKey);
    }

    private String buildKey(String sessionId, String field) {
        return KEY_PREFIX + sessionId + ":" + field;
    }
}
