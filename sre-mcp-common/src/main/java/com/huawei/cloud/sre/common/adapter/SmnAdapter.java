package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.smn.v2.SmnClient;
import com.huaweicloud.sdk.smn.v2.model.PublishMessageRequest;
import com.huaweicloud.sdk.smn.v2.model.PublishMessageRequestBody;
import com.huaweicloud.sdk.smn.v2.region.SmnRegion;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 华为云 SMN（消息通知服务）适配器。
 *
 * <p>用于向运维人员发送告警通知（短信、邮件、Webhook 等）。
 */
@Component
public class SmnAdapter {

    private static final Logger log = LoggerFactory.getLogger(SmnAdapter.class);
    private static final String SERVICE_NAME = "SMN";

    private final SmnClient client;
    private final MeterRegistry meterRegistry;

    /**
     * @param credentialProvider 华为云凭证提供者
     * @param region             华为云区域
     * @param meterRegistry      Micrometer 指标注册表
     */
    @Autowired
    public SmnAdapter(
            HuaweiCloudCredentialProvider credentialProvider,
            @Value("${huaweicloud.region:cn-north-4}") String region,
            MeterRegistry meterRegistry
    ) {
        SmnClient tempClient = null;
        try {
            tempClient = SmnClient.newBuilder()
                    .withCredential(credentialProvider.getCredentials())
                    .withRegion(SmnRegion.valueOf(region))
                    .build();
        } catch (Exception e) {
            log.warn("SmnAdapter disabled (region not supported): {}", e.getMessage());
        }
        this.client = tempClient;
        this.meterRegistry = meterRegistry;
    }

    /** 测试用构造器，允许注入 Mock SmnClient。 */
    SmnAdapter(SmnClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 向指定 SMN 主题发布消息（通知）。
     *
     * @param topicUrn 主题 URN，如 urn:smn:cn-north-4:{project_id}:{topic_name}
     * @param subject  消息主题（用于邮件通知的标题）
     * @param message  消息正文
     * @return SMN 返回的消息 ID
     * @throws HuaweiCloudException 若 SMN API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public String publishMessage(String topicUrn, String subject, String message) {
        log.info("SMN publishMessage topicUrn={} subject={}", topicUrn, subject);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var body = new PublishMessageRequestBody()
                    .withSubject(subject)
                    .withMessage(message);

            var request = new PublishMessageRequest()
                    .withTopicUrn(topicUrn)
                    .withBody(body);

            var response = client.publishMessage(request);
            String messageId = response.getMessageId() != null ? response.getMessageId() : "";
            log.info("SMN publishMessage success topicUrn={} messageId={}", topicUrn, messageId);
            return messageId;
        } catch (ServiceResponseException e) {
            log.error("SMN publishMessage failed topicUrn={} httpStatus={}", topicUrn, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, "SMN 消息发布失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "publishMessage"));
        }
    }
    /** 检查 client 是否可用（区域不支持时为 null）。*/
    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException("Smn", "Smn adapter not available in current region", 503, "REGION_NOT_SUPPORTED", null, null);
        }
    }

}