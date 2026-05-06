package com.huawei.cloud.sre.monitor.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 监控事件 Kafka 生产者。
 *
 * <p>将告警触发、基线更新、异常检测等事件发布到 Kafka 事件总线，
 * 供下游（RCA、Remediation）异步消费。
 */
@Component
public class MonitorEventProducer {

    private static final Logger log = LoggerFactory.getLogger(MonitorEventProducer.class);

    /** 告警事件 Topic。 */
    public static final String ALERTS_TOPIC = "sre-monitor-alerts";

    /** 基线变更事件 Topic。 */
    public static final String BASELINES_TOPIC = "sre-monitor-baselines";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * @param kafkaTemplate Kafka 模板，local profile 下为 null（不发送事件）
     * @param objectMapper  JSON 序列化
     */
    public MonitorEventProducer(@Autowired(required = false) @Nullable KafkaTemplate<String, String> kafkaTemplate,
                                ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布监控事件到指定 Topic。
     *
     * @param topic Topic 名称
     * @param event 监控事件
     */
    public void publish(String topic, MonitorEvent event) {
        if (kafkaTemplate == null) {
            log.debug("Kafka not available (local mode), skipping event: type={} service={}", event.eventType(), event.service());
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(event);
            String key = event.service() + ":" + event.metric();
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, key, payload);
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish event to topic={} key={}: {}", topic, key, ex.getMessage());
                } else {
                    log.debug("Published event topic={} key={} offset={}",
                            topic, key, result.getRecordMetadata().offset());
                }
            });
            log.info("MonitorEventProducer.publish topic={} eventType={} service={}",
                    topic, event.eventType(), event.service());
        } catch (Exception e) {
            log.error("MonitorEventProducer.publish failed topic={} eventType={}: {}", topic, event.eventType(), e.getMessage());
        }
    }

    /**
     * 发布告警触发事件。
     *
     * @param event 监控事件
     */
    public void publishAlert(MonitorEvent event) {
        publish(ALERTS_TOPIC, event);
    }

    /**
     * 发布基线变更事件。
     *
     * @param event 监控事件
     */
    public void publishBaselineUpdate(MonitorEvent event) {
        publish(BASELINES_TOPIC, event);
    }
}
