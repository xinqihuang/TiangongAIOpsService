package com.huawei.cloud.sre.monitor.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.monitor.dto.AlertHandlingResult;
import com.huawei.cloud.sre.monitor.service.EmergencyPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * 华为云 CES 告警 Webhook 接收端点。
 *
 * <p>接入路径（需在 CES 告警规则中配置 SMN Topic，并将本服务 URL 添加为 HTTP/HTTPS 订阅端点）：
 * <pre>
 *   POST https://{your-service-host}:8002/webhooks/ces/alarm
 * </pre>
 *
 * <p>完整告警链路：
 * <pre>
 *   CES 指标超阈值
 *     → SMN Topic (HTTP 订阅)
 *       → POST /webhooks/ces/alarm
 *         → [SubscriptionConfirmation] 自动回调 SubscribeURL 完成订阅确认
 *         → [Notification] 解析 CES 告警
 *           → EmergencyPlanService.handle()
 *             → RAG 检索应急预案 (命中) → 返回预案步骤
 *             → LLM 自主决策        (未命中) → 返回 AI 决策
 * </pre>
 *
 * <p><strong>SMN 订阅配置步骤：</strong>
 * <ol>
 *   <li>华为云控制台 → 简单消息通知 SMN → 主题 → 新建主题，如 {@code ces-alarm-topic}</li>
 *   <li>添加订阅：协议选 HTTPS，端点填本服务地址 {@code https://host:8002/webhooks/ces/alarm}</li>
 *   <li>服务收到 SubscriptionConfirmation 后自动确认，订阅状态变为"已确认"</li>
 *   <li>CES → 告警规则 → 选择上述 SMN 主题，触发后即推送到本端点</li>
 * </ol>
 */
@RestController
@RequestMapping("/webhooks/ces")
public class CesAlarmWebhookController {

    private static final Logger log = LoggerFactory.getLogger(CesAlarmWebhookController.class);

    /** RAG 命中阈值：相似度 ≥ 0.65 视为命中存量应急预案。 */
    private static final double RAG_THRESHOLD = 0.65;

    private final EmergencyPlanService emergencyPlanService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    /**
     * @param emergencyPlanService RAG + LLM 告警处置服务
     * @param objectMapper         JSON 解析器
     */
    public CesAlarmWebhookController(
            EmergencyPlanService emergencyPlanService,
            ObjectMapper objectMapper
    ) {
        this.emergencyPlanService = emergencyPlanService;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    /**
     * 接收 CES 告警推送（经由 SMN HTTP/HTTPS 订阅）。
     *
     * <p>两种场景：
     * <ul>
     *   <li>{@code SubscriptionConfirmation}：自动 GET SubscribeURL 完成订阅激活</li>
     *   <li>{@code Notification}：解析 CES 告警，触发 RAG → LLM 处置流程</li>
     * </ul>
     *
     * @param rawBody SMN 推送的原始 JSON 字符串
     * @return 200 OK（SMN 要求端点 5 秒内响应，告警处置日志异步写入）
     */
    @PostMapping("/alarm")
    public ResponseEntity<String> onAlarm(@RequestBody String rawBody) {
        log.debug("CesAlarmWebhook received body={}", rawBody);
        try {
            SmnNotification notification = objectMapper.readValue(rawBody, SmnNotification.class);
            return switch (notification.type()) {
                case "SubscriptionConfirmation" -> confirmSubscription(notification);
                case "Notification" -> handleNotification(notification);
                default -> {
                    log.warn("CesAlarmWebhook unknown type={}", notification.type());
                    yield ResponseEntity.ok("ignored");
                }
            };
        } catch (Exception e) {
            log.error("CesAlarmWebhook parse error: {}", e.getMessage(), e);
            // 返回 200 避免 SMN 无限重试
            return ResponseEntity.ok("parse error, skipped");
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private ResponseEntity<String> confirmSubscription(SmnNotification notification) {
        String url = notification.subscribeUrl();
        if (url == null || url.isBlank()) {
            log.warn("CesAlarmWebhook SubscriptionConfirmation missing SubscribeURL");
            return ResponseEntity.ok("no SubscribeURL");
        }
        try {
            restClient.get().uri(url).retrieve().toBodilessEntity();
            log.info("CesAlarmWebhook SMN subscription confirmed topicArn={}", notification.topicArn());
            return ResponseEntity.ok("confirmed");
        } catch (Exception e) {
            log.error("CesAlarmWebhook failed to confirm subscription url={}: {}", url, e.getMessage());
            return ResponseEntity.ok("confirm failed");
        }
    }

    private ResponseEntity<String> handleNotification(SmnNotification notification) {
        String message = notification.message();
        if (message == null || message.isBlank()) {
            log.warn("CesAlarmWebhook Notification has empty Message");
            return ResponseEntity.ok("empty message");
        }

        CesAlarmEvent alarm;
        try {
            alarm = objectMapper.readValue(message, CesAlarmEvent.class);
        } catch (Exception e) {
            log.error("CesAlarmWebhook failed to parse CES alarm: {}", e.getMessage());
            return ResponseEntity.ok("alarm parse failed");
        }

        log.info("CesAlarmWebhook alarm received alarmId={} name={} status={} level={}",
                alarm.alarmId(), alarm.alarmName(), alarm.alarmStatus(), alarm.alarmLevel());

        // 只处理触发告警，忽略恢复通知
        if (!"alarm".equalsIgnoreCase(alarm.alarmStatus())) {
            log.info("CesAlarmWebhook skip recovery alarmId={} status={}", alarm.alarmId(), alarm.alarmStatus());
            return ResponseEntity.ok("recovery ignored");
        }

        String description = alarm.toDescription();
        String service = alarm.inferService();
        String severity = alarm.severity();

        log.info("CesAlarmWebhook dispatching to EmergencyPlanService alarmId={} service={} severity={}",
                alarm.alarmId(), service, severity);

        AlertHandlingResult result = emergencyPlanService.handle(description, service, severity, RAG_THRESHOLD);

        log.info("CesAlarmWebhook handled alarmId={} source={} planTitle={} steps={}",
                alarm.alarmId(), result.source(), result.planTitle(), result.responseSteps().size());
        log.info("CesAlarmWebhook response steps: {}", result.responseSteps());

        return ResponseEntity.ok("handled:" + result.source());
    }
}
