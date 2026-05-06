package com.huawei.cloud.sre.monitor.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MonitorEventProducerIT {

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    private KafkaTemplate<String, String> kafkaTemplate;
    private MonitorEventProducer producer;
    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        );
        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
        producer = new MonitorEventProducer(kafkaTemplate, new ObjectMapper());

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(List.of(MonitorEventProducer.ALERTS_TOPIC));
    }

    @AfterEach
    void tearDown() {
        consumer.close();
    }

    @Test
    void publishAlert_sendsMessageToAlertsTopic() throws Exception {
        MonitorEvent event = new MonitorEvent(
                UUID.randomUUID().toString(),
                "ALERT_TRIGGERED",
                "order-service",
                "cpu_usage",
                "HIGH",
                "CPU spike detected",
                Map.of("value", 95.0),
                Instant.now()
        );

        producer.publishAlert(event);
        Thread.sleep(500);

        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
        assertThat(records.count()).isGreaterThanOrEqualTo(1);

        ConsumerRecord<String, String> record = records.iterator().next();
        assertThat(record.key()).isEqualTo("order-service:cpu_usage");
        assertThat(record.value()).contains("ALERT_TRIGGERED");
        assertThat(record.value()).contains("order-service");
    }

    @Test
    void publishBaselineUpdate_sendsToBaselinesTopic() throws Exception {
        consumer.unsubscribe();
        consumer.subscribe(List.of(MonitorEventProducer.BASELINES_TOPIC));

        MonitorEvent event = new MonitorEvent(
                UUID.randomUUID().toString(),
                "BASELINE_UPDATED",
                "payment-service",
                "latency_p99",
                "LOW",
                "Baseline updated",
                Map.of("mean", 120.0),
                Instant.now()
        );

        producer.publishBaselineUpdate(event);
        Thread.sleep(500);

        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
        assertThat(records.count()).isGreaterThanOrEqualTo(1);
        assertThat(records.iterator().next().value()).contains("BASELINE_UPDATED");
    }
}
