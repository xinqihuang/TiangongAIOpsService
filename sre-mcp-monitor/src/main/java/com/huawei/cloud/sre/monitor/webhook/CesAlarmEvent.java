package com.huawei.cloud.sre.monitor.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 华为云 CES（云监控）告警事件，即 SMN 消息体中 {@code Message} 字段解析后的结构。
 *
 * <p>告警级别 {@link #alarmLevel()} 含义：
 * <ul>
 *   <li>1 = 紧急（CRITICAL）</li>
 *   <li>2 = 重要（HIGH）</li>
 *   <li>3 = 次要（MEDIUM）</li>
 *   <li>4 = 提示（LOW）</li>
 * </ul>
 *
 * <p>{@link #alarmStatus()} 值：{@code alarm}（触发）/ {@code ok}（恢复）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CesAlarmEvent(

        @JsonProperty("alarm_id")
        String alarmId,

        @JsonProperty("alarm_name")
        String alarmName,

        /** 触发状态：{@code alarm} = 告警触发，{@code ok} = 恢复正常。 */
        @JsonProperty("alarm_status")
        String alarmStatus,

        /** 1=CRITICAL / 2=HIGH / 3=MEDIUM / 4=LOW */
        @JsonProperty("alarm_level")
        int alarmLevel,

        /** CES 命名空间，如 SYS.ECS / SYS.CCE / PAAS.CONTAINER */
        @JsonProperty("namespace")
        String namespace,

        @JsonProperty("metric_name")
        String metricName,

        @JsonProperty("alarm_description")
        String alarmDescription,

        /** 触发条件，如 >= 90 */
        @JsonProperty("comparison_operator")
        String comparisonOperator,

        @JsonProperty("value")
        double threshold,

        @JsonProperty("unit")
        String unit,

        /** 资源维度，如 [{name:"instance_id", value:"i-xxx"}] */
        @JsonProperty("dimensions")
        List<Map<String, String>> dimensions,

        /** 告警触发时间戳（毫秒） */
        @JsonProperty("timestamp")
        long timestamp
) {

    /** 将 CES 告警级别整数映射为 SRE 严重程度字符串。 */
    public String severity() {
        return switch (alarmLevel) {
            case 1 -> "CRITICAL";
            case 2 -> "HIGH";
            case 3 -> "MEDIUM";
            default -> "LOW";
        };
    }

    /**
     * 构建告警描述字符串，用于向量检索和 LLM 输入。
     * 格式：{alarmName} | namespace={namespace} metric={metricName} {op}{threshold}{unit} | {dimensions}
     */
    public String toDescription() {
        String dimStr = dimensions == null ? "" : dimensions.stream()
                .map(d -> d.getOrDefault("name", "") + "=" + d.getOrDefault("value", ""))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        String desc = alarmDescription != null && !alarmDescription.isBlank()
                ? " | desc=" + alarmDescription : "";
        return alarmName + " | namespace=" + namespace
                + " metric=" + metricName
                + " " + comparisonOperator + threshold + unit
                + (dimStr.isBlank() ? "" : " | " + dimStr)
                + desc;
    }

    /** 从 namespace 和 dimensions 推断服务名，用于 handleAlert 的 service 参数。 */
    public String inferService() {
        if (dimensions != null) {
            for (Map<String, String> dim : dimensions) {
                String name = dim.getOrDefault("name", "");
                if ("appName".equals(name) || "service_id".equals(name)
                        || "workload_name".equals(name)) {
                    return dim.getOrDefault("value", namespace);
                }
            }
        }
        return namespace; // fallback: 用 namespace 作为服务标识
    }
}
