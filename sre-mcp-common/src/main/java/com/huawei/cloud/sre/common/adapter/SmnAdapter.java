package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huawei.cloud.sre.common.util.Messages;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.smn.v2.SmnClient;
import com.huaweicloud.sdk.smn.v2.model.AddSubscriptionRequest;
import com.huaweicloud.sdk.smn.v2.model.AddSubscriptionRequestBody;
import com.huaweicloud.sdk.smn.v2.model.CreateTopicRequest;
import com.huaweicloud.sdk.smn.v2.model.CreateTopicRequestBody;
import com.huaweicloud.sdk.smn.v2.model.ListSubscriptionsByTopicRequest;
import com.huaweicloud.sdk.smn.v2.model.ListTopicsRequest;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 华为云 SMN（消息通知服务）适配器。
 *
 * <p>用于向运维人员发送告警通知（短信、邮件、Webhook 等），以及管理主题与订阅。
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
        requireClient();
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
                    SERVICE_NAME, Messages.get("err.smn.message.publish", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "publishMessage"));
        }
    }

    /**
     * 列出当前项目下所有 SMN 主题。
     *
     * @param limit  最大返回条数（1-100）
     * @return 主题列表，每项包含 topicUrn、name、displayName、pushPolicy 字段
     * @throws HuaweiCloudException 若 SMN API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listTopics(int limit) {
        requireClient();
        int clampedLimit = Math.max(1, Math.min(limit, 100));
        log.info("SMN listTopics limit={}", clampedLimit);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListTopicsRequest().withOffset(0).withLimit(clampedLimit);
            var response = client.listTopics(request);

            List<Map<String, String>> topics = new ArrayList<>();
            if (response.getTopics() != null) {
                for (var topic : response.getTopics()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("topicUrn", topic.getTopicUrn() != null ? topic.getTopicUrn() : "");
                    item.put("name", topic.getName() != null ? topic.getName() : "");
                    item.put("displayName", topic.getDisplayName() != null ? topic.getDisplayName() : "");
                    item.put("pushPolicy", topic.getPushPolicy() != null ? topic.getPushPolicy().toString() : "");
                    topics.add(item);
                }
            }
            log.info("SMN listTopics success count={}", topics.size());
            return topics;
        } catch (ServiceResponseException e) {
            log.error("SMN listTopics failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.smn.topic.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listTopics"));
        }
    }

    /**
     * 创建 SMN 主题。
     *
     * @param name        主题名称（唯一，1-255 个字符，字母数字下划线）
     * @param displayName 展示名称（用于邮件通知发件人字段）
     * @return 创建的主题 URN
     * @throws HuaweiCloudException 若 SMN API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public String createTopic(String name, String displayName) {
        requireClient();
        log.info("SMN createTopic name={} displayName={}", name, displayName);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var body = new CreateTopicRequestBody()
                    .withName(name)
                    .withDisplayName(displayName);
            var response = client.createTopic(new CreateTopicRequest().withBody(body));
            String topicUrn = response.getTopicUrn() != null ? response.getTopicUrn() : "";
            log.info("SMN createTopic success name={} topicUrn={}", name, topicUrn);
            return topicUrn;
        } catch (ServiceResponseException e) {
            log.error("SMN createTopic failed name={} httpStatus={}", name, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.smn.topic.create", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "createTopic"));
        }
    }

    /**
     * 为指定主题新增订阅。
     *
     * @param topicUrn 主题 URN
     * @param protocol 协议类型，如 email、sms、http、https、functionstage
     * @param endpoint 订阅终端地址（邮箱、电话号码、URL 等）
     * @param remark   订阅备注
     * @return 订阅 URN
     * @throws HuaweiCloudException 若 SMN API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public String createSubscription(String topicUrn, String protocol, String endpoint, String remark) {
        requireClient();
        log.info("SMN createSubscription topicUrn={} protocol={} endpoint={}", topicUrn, protocol, endpoint);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var body = new AddSubscriptionRequestBody()
                    .withProtocol(protocol)
                    .withEndpoint(endpoint)
                    .withRemark(remark);
            var request = new AddSubscriptionRequest()
                    .withTopicUrn(topicUrn)
                    .withBody(body);
            var response = client.addSubscription(request);
            String subscriptionUrn = response.getSubscriptionUrn() != null ? response.getSubscriptionUrn() : "";
            log.info("SMN createSubscription success topicUrn={} subscriptionUrn={}", topicUrn, subscriptionUrn);
            return subscriptionUrn;
        } catch (ServiceResponseException e) {
            log.error("SMN createSubscription failed topicUrn={} httpStatus={}", topicUrn, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.smn.subscription.create", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "createSubscription"));
        }
    }

    /**
     * 列出指定主题的所有订阅。
     *
     * @param topicUrn 主题 URN
     * @return 订阅列表，每项包含 subscriptionUrn、protocol、endpoint、status 字段
     * @throws HuaweiCloudException 若 SMN API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listSubscriptions(String topicUrn) {
        requireClient();
        log.info("SMN listSubscriptions topicUrn={}", topicUrn);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListSubscriptionsByTopicRequest().withTopicUrn(topicUrn).withOffset(0).withLimit(100);
            var response = client.listSubscriptionsByTopic(request);

            List<Map<String, String>> subscriptions = new ArrayList<>();
            if (response.getSubscriptions() != null) {
                for (var sub : response.getSubscriptions()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("subscriptionUrn", sub.getSubscriptionUrn() != null ? sub.getSubscriptionUrn() : "");
                    item.put("protocol", sub.getProtocol() != null ? sub.getProtocol() : "");
                    item.put("endpoint", sub.getEndpoint() != null ? sub.getEndpoint() : "");
                    item.put("status", sub.getStatus() != null ? sub.getStatus().toString() : "");
                    item.put("remark", sub.getRemark() != null ? sub.getRemark() : "");
                    subscriptions.add(item);
                }
            }
            log.info("SMN listSubscriptions success topicUrn={} count={}", topicUrn, subscriptions.size());
            return subscriptions;
        } catch (ServiceResponseException e) {
            log.error("SMN listSubscriptions failed topicUrn={} httpStatus={}", topicUrn, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.smn.subscription.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listSubscriptions"));
        }
    }

    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException(SERVICE_NAME, "SMN adapter not available in current region",
                    503, "REGION_NOT_SUPPORTED", null, null);
        }
    }
}
