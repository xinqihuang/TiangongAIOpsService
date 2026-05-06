package com.huawei.cloud.sre.monitor.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.monitor.service.AlertAggregator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 监控事件 Kafka 消费者。
 *
 * <p>消费 {@code sre-monitor-alerts} Topic 中的告警事件，触发告警聚合、
 * 活跃告警记录等后处理逻辑。
 *
 * <p>{@code local} profile 下不加载，避免需要 Docker 运行 Kafka。
 */
@Component
@Profile("!local")
public class MonitorEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(MonitorEventConsumer.class);

    private final AlertAggregator alertAggregator;
    private final ObjectMapper objectMapper;

    /**
     * @param alertAggregator 告警聚合服务
     * @param objectMapper    JSON 反序列化
     */
    public MonitorEventConsumer(AlertAggregator alertAggregator, ObjectMapper objectMapper) {
        this.alertAggregator = alertAggregator;
        this.objectMapper = objectMapper;
    }

    /**
     * 消费告警事件，记录活跃告警并执行聚合触发。
     *
     * @param record 消费到的 Kafka 记录
     * @param ack    手动确认
     */
    @KafkaListener(
            topics = MonitorEventProducer.ALERTS_TOPIC,
            groupId = "${spring.kafka.consumer.group-id:sre-monitor-group}",
            containerFactory = "monitorKafkaListenerContainerFactory"
    )
    public void onAlertEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("MonitorEventConsumer.onAlertEvent key={} partition={} offset={}",
                record.key(), record.partition(), record.offset());
        try {
            MonitorEvent event = objectMapper.readValue(record.value(), MonitorEvent.class);
            processAlertEvent(event, record.value());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("MonitorEventConsumer.onAlertEvent failed key={}: {}", record.key(), e.getMessage());
            ack.acknowledge();
        }
    }

    private void processAlertEvent(MonitorEvent event, String rawJson) {
        if ("ALERT_TRIGGERED".equals(event.eventType())) {
            alertAggregator.recordActiveAlert(event.eventId(), rawJson);
            log.info("Recorded active alert eventId={} service={} severity={}",
                    event.eventId(), event.service(), event.severity());
        } else if ("ALERT_RESOLVED".equals(event.eventType())) {
            alertAggregator.resolveAlert(event.eventId());
            log.info("Resolved alert eventId={} service={}", event.eventId(), event.service());
        }
    }
}
