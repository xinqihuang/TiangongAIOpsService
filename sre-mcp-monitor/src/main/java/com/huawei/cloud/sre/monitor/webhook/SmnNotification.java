package com.huawei.cloud.sre.monitor.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 华为云 SMN HTTP 推送信封。
 *
 * <p>SMN 向 HTTP 订阅端点发送的外层 JSON 结构，字段名为 PascalCase。
 * 有两种 Type：
 * <ul>
 *   <li>{@code SubscriptionConfirmation} — 订阅确认（首次），需调用 {@link #subscribeUrl()} 确认</li>
 *   <li>{@code Notification} — 告警通知，{@link #message()} 为 CES 告警 JSON 字符串</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SmnNotification(

        @JsonProperty("Type")
        String type,

        @JsonProperty("MessageId")
        String messageId,

        @JsonProperty("TopicArn")
        String topicArn,

        @JsonProperty("Subject")
        String subject,

        /** 告警通知时为 CES 告警 JSON 字符串；订阅确认时为说明文字。 */
        @JsonProperty("Message")
        String message,

        @JsonProperty("Timestamp")
        String timestamp,

        /** 仅 SubscriptionConfirmation 时存在，调用此 URL 确认订阅。 */
        @JsonProperty("SubscribeURL")
        String subscribeUrl,

        @JsonProperty("Signature")
        String signature,

        @JsonProperty("SigningCertURL")
        String signingCertUrl
) {}
