package com.huawei.cloud.sre.monitor.config;

import com.huawei.cloud.sre.common.adapter.AomAdapter;
import com.huawei.cloud.sre.common.dto.MetricResult;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * AOM 调试接口，仅在 local profile 下加载。
 *
 * <p>用于本地开发时直接通过 HTTP 验证 AOM API 连通性，无需走 MCP 协议。
 */
@RestController
@RequestMapping("/debug/aom")
@Profile("local")
public class AomDebugController {

    private final AomAdapter aomAdapter;

    public AomDebugController(AomAdapter aomAdapter) {
        this.aomAdapter = aomAdapter;
    }

    /**
     * 查询当前活跃告警。
     *
     * <p>示例：GET /debug/aom/alerts?minutes=60
     *
     * @param minutes 查询最近多少分钟，默认 60
     * @return 活跃告警列表
     */
    @GetMapping("/alerts")
    public List<Map<String, Object>> listAlerts(
            @RequestParam(defaultValue = "60") int minutes) {
        return aomAdapter.listActiveAlarms(minutes);
    }

    /**
     * 查询指标时序数据。
     *
     * <p>示例：GET /debug/aom/metrics?service=my-svc&metric=cpu_usage&periodSeconds=60&rangeMinutes=30
     *
     * @param service       服务名
     * @param metric        指标名
     * @param periodSeconds 采样间隔（秒），默认 60
     * @param rangeMinutes  查询范围（分钟），默认 30
     * @return 指标时序数据
     */
    @GetMapping("/metrics")
    public MetricResult queryMetrics(
            @RequestParam String service,
            @RequestParam String metric,
            @RequestParam(defaultValue = "60") int periodSeconds,
            @RequestParam(defaultValue = "30") int rangeMinutes) {
        Instant end = Instant.now();
        Instant start = end.minusSeconds(rangeMinutes * 60L);
        return aomAdapter.queryMetric(service, metric, start, end, periodSeconds);
    }
}
