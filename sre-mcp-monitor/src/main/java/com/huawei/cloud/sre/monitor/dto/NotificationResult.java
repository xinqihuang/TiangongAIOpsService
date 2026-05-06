package com.huawei.cloud.sre.monitor.dto;

import java.time.Instant;

/**
 * 通知发送结果。
 *
 * @param messageId  SMN 消息 ID
 * @param topicUrn   目标主题 URN
 * @param subject    通知标题
 * @param success    是否发送成功
 * @param sentAt     发送时间
 * @param errorMsg   失败原因（success=false 时填写）
 */
public record NotificationResult(
        String messageId,
        String topicUrn,
        String subject,
        boolean success,
        Instant sentAt,
        String errorMsg
) {

    /** 构造成功结果的工厂方法。 */
    public static NotificationResult ok(String messageId, String topicUrn, String subject) {
        return new NotificationResult(messageId, topicUrn, subject, true, Instant.now(), null);
    }

    /** 构造失败结果的工厂方法。 */
    public static NotificationResult failed(String topicUrn, String subject, String error) {
        return new NotificationResult(null, topicUrn, subject, false, Instant.now(), error);
    }
}
