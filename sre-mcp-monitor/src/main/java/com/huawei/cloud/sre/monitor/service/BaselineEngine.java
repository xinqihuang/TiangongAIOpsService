package com.huawei.cloud.sre.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.monitor.dto.AnomalyResult;
import com.huawei.cloud.sre.monitor.dto.BaselineStatus;
import com.huawei.cloud.sre.monitor.dto.CapacityForecast;
import com.huawei.cloud.sre.monitor.repository.BaselineEntity;
import com.huawei.cloud.sre.monitor.repository.BaselineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 动态基线引擎。
 *
 * <p>使用 EWMA（指数加权移动平均）+ Welford 在线方差算法跟踪每个服务/指标的动态基线，
 * 并基于 3σ 准则判定异常。基线状态同时缓存到 Redis（降低 DB 读压力）并持久化到 PostgreSQL。
 *
 * <h3>算法说明</h3>
 * <ul>
 *   <li>EWMA 均值：{@code mean_new = α * x + (1-α) * mean_old}</li>
 *   <li>EWMA 方差（Welford 变体）：{@code var_new = (1-α) * (var_old + α * (x - mean_old)²)}</li>
 *   <li>异常判定：{@code |x - mean| > 3 * σ}</li>
 * </ul>
 */
@Service
public class BaselineEngine {

    private static final Logger log = LoggerFactory.getLogger(BaselineEngine.class);
    private static final String REDIS_KEY_PREFIX = "monitor:baseline:";
    private static final Duration REDIS_TTL = Duration.ofHours(2);
    private static final int MIN_STABLE_SAMPLES = 30;
    private static final double DEFAULT_ALPHA = 0.2;

    private final BaselineRepository baselineRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final double alpha;

    /**
     * @param baselineRepository 基线 JPA 仓库
     * @param redisTemplate      Redis 模板
     * @param objectMapper       JSON 序列化
     * @param alpha              EWMA 平滑系数（默认 0.2）
     */
    public BaselineEngine(
            BaselineRepository baselineRepository,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${monitor.baseline.alpha:0.2}") double alpha
    ) {
        this.baselineRepository = baselineRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.alpha = alpha;
    }

    /**
     * 向基线提交一批新观测值，更新 EWMA 均值和方差。
     *
     * @param service      服务名称
     * @param metric       指标名称
     * @param observations 新观测值列表（按时间顺序）
     * @return 更新后的基线状态
     */
    public BaselineStatus updateBaseline(String service, String metric, List<Double> observations) {
        log.info("BaselineEngine.updateBaseline service={} metric={} count={}", service, metric, observations.size());
        if (observations.isEmpty()) {
            return getBaselineStatus(service, metric);
        }

        BaselineEntity entity = baselineRepository.findByServiceAndMetric(service, metric)
                .orElseGet(() -> new BaselineEntity(service, metric, observations.get(0), 0.0, 0L, alpha));

        double mean = entity.getEwmaMean();
        double variance = entity.getEwmaVariance();
        long count = entity.getSampleCount();

        for (double x : observations) {
            double delta = x - mean;
            mean = alpha * x + (1.0 - alpha) * mean;
            variance = (1.0 - alpha) * (variance + alpha * delta * delta);
            count++;
        }

        entity.setEwmaMean(mean);
        entity.setEwmaVariance(variance);
        entity.setSampleCount(count);
        baselineRepository.save(entity);

        BaselineStatus status = buildStatus(entity);
        cacheBaseline(service, metric, status);
        log.info("BaselineEngine.updateBaseline done service={} metric={} mean={:.4f} stdDev={:.4f}",
                service, metric, mean, Math.sqrt(variance));
        return status;
    }

    /**
     * 检测单个观测值是否异常（基于当前基线的 3σ 准则）。
     *
     * @param service      服务名称
     * @param metric       指标名称
     * @param currentValue 当前观测值
     * @return 异常检测结果
     */
    public AnomalyResult detectAnomaly(String service, String metric, double currentValue) {
        log.info("BaselineEngine.detectAnomaly service={} metric={} value={}", service, metric, currentValue);
        BaselineStatus baseline = getBaselineStatus(service, metric);

        double stdDev = baseline.stdDev();
        double mean = baseline.ewmaMean();

        if (stdDev < 1e-9) {
            return new AnomalyResult(service, metric, Instant.now(), false,
                    currentValue, mean, stdDev, 0.0, "NORMAL", "NORMAL",
                    "Baseline not yet stable (zero variance)", List.of());
        }

        double sigma = Math.abs(currentValue - mean) / stdDev;
        boolean isAnomaly = sigma >= 3.0 && baseline.isStable();
        String anomalyType = determineAnomalyType(currentValue, mean, isAnomaly);
        String severity = determineSeverity(sigma, isAnomaly);
        String suggestion = buildSuggestion(service, metric, anomalyType, sigma);

        log.info("BaselineEngine.detectAnomaly result service={} metric={} sigma={:.2f} anomaly={}",
                service, metric, sigma, isAnomaly);
        return new AnomalyResult(service, metric, Instant.now(), isAnomaly,
                currentValue, mean, stdDev, sigma, severity, anomalyType, suggestion, List.of());
    }

    /**
     * 基于历史观测值列表进行线性趋势预测。
     *
     * @param service        服务名称
     * @param metric         指标名称
     * @param historicalData 历史数据点（按时间升序）
     * @param forecastHours  预测时间范围（小时）
     * @return 容量预测结果
     */
    public CapacityForecast forecastCapacity(String service, String metric,
                                             List<Double> historicalData, int forecastHours) {
        log.info("BaselineEngine.forecastCapacity service={} metric={} dataPoints={} hours={}",
                service, metric, historicalData.size(), forecastHours);

        if (historicalData.size() < 2) {
            double current = historicalData.isEmpty() ? 0.0 : historicalData.get(0);
            return new CapacityForecast(service, metric, forecastHours, current, current,
                    "STABLE", 0.0, null, "Insufficient data for forecast", List.of(), Instant.now());
        }

        double slope = computeLinearSlope(historicalData);
        double current = historicalData.get(historicalData.size() - 1);
        double forecastedValue = current + slope * forecastHours;
        String trend = slope > 0.01 ? "INCREASING" : slope < -0.01 ? "DECREASING" : "STABLE";

        Instant exhaustionTime = null;
        if (slope > 0 && current > 0) {
            double hoursToDouble = current / slope;
            if (hoursToDouble > 0 && hoursToDouble < 720) {
                exhaustionTime = Instant.now().plus(Duration.ofMinutes((long) (hoursToDouble * 60)));
            }
        }

        List<CapacityForecast.ForecastPoint> points = buildForecastPoints(current, slope, forecastHours);
        BaselineStatus baseline = getBaselineStatus(service, metric);
        double confidence = baseline.stdDev() > 0 ? baseline.stdDev() * 2 : Math.abs(current * 0.1);
        String recommendation = buildCapacityRecommendation(service, metric, trend, forecastedValue, current);

        return new CapacityForecast(service, metric, forecastHours, current, forecastedValue,
                trend, slope, exhaustionTime, recommendation, points, Instant.now());
    }

    /**
     * 获取指定服务/指标的当前基线状态（优先读 Redis 缓存）。
     *
     * @param service 服务名
     * @param metric  指标名
     * @return 基线状态（不存在时返回空基线）
     */
    public BaselineStatus getBaselineStatus(String service, String metric) {
        try {
            String cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + service + ":" + metric);
            if (cached != null) {
                return objectMapper.readValue(cached, BaselineStatus.class);
            }
        } catch (Exception e) {
            log.debug("Redis unavailable or cache miss for {}:{}: {}", service, metric, e.getMessage());
        }

        return baselineRepository.findByServiceAndMetric(service, metric)
                .map(this::buildStatus)
                .orElse(emptyBaseline(service, metric));
    }

    private BaselineStatus buildStatus(BaselineEntity entity) {
        double stdDev = Math.sqrt(entity.getEwmaVariance());
        double mean = entity.getEwmaMean();
        boolean stable = entity.getSampleCount() >= MIN_STABLE_SAMPLES;
        return new BaselineStatus(
                entity.getService(), entity.getMetric(),
                mean, entity.getEwmaVariance(), stdDev,
                mean + 3.0 * stdDev, mean - 3.0 * stdDev,
                entity.getSampleCount(), entity.getLastUpdated(), stable
        );
    }

    private BaselineStatus emptyBaseline(String service, String metric) {
        return new BaselineStatus(service, metric, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, Instant.now(), false);
    }

    private void cacheBaseline(String service, String metric, BaselineStatus status) {
        try {
            String json = objectMapper.writeValueAsString(status);
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + service + ":" + metric, json, REDIS_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache baseline for {}:{}: {}", service, metric, e.getMessage());
        }
    }

    private String determineAnomalyType(double current, double mean, boolean isAnomaly) {
        if (!isAnomaly) return "NORMAL";
        return current > mean ? "SPIKE" : "DROP";
    }

    private String determineSeverity(double sigma, boolean isAnomaly) {
        if (!isAnomaly) return "NORMAL";
        if (sigma >= 5.0) return "CRITICAL";
        if (sigma >= 4.0) return "HIGH";
        if (sigma >= 3.0) return "MEDIUM";
        return "LOW";
    }

    private String buildSuggestion(String service, String metric, String anomalyType, double sigma) {
        return switch (anomalyType) {
            case "SPIKE" -> "Investigate sudden %s increase on %s (%.1fσ). Check for traffic spike or resource leak."
                    .formatted(metric, service, sigma);
            case "DROP" -> "Investigate sudden %s drop on %s (%.1fσ). Check for service failure or data loss."
                    .formatted(metric, service, sigma);
            default -> "Metric %s on %s is within normal range.".formatted(metric, service);
        };
    }

    private double computeLinearSlope(List<Double> data) {
        int n = data.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += data.get(i);
            sumXY += (double) i * data.get(i);
            sumX2 += (double) i * i;
        }
        double denominator = n * sumX2 - sumX * sumX;
        if (Math.abs(denominator) < 1e-9) return 0.0;
        return (n * sumXY - sumX * sumY) / denominator;
    }

    private List<CapacityForecast.ForecastPoint> buildForecastPoints(double current,
                                                                      double slope, int hours) {
        List<CapacityForecast.ForecastPoint> points = new ArrayList<>();
        double confidence = Math.abs(current * 0.1) + 1.0;
        for (int h = 1; h <= hours; h++) {
            double forecast = current + slope * h;
            points.add(new CapacityForecast.ForecastPoint(
                    Instant.now().plus(Duration.ofHours(h)),
                    forecast,
                    forecast + confidence,
                    Math.max(0, forecast - confidence)
            ));
        }
        return points;
    }

    private String buildCapacityRecommendation(String service, String metric,
                                               String trend, double forecasted, double current) {
        return switch (trend) {
            case "INCREASING" -> "Consider scaling %s: %s projected to reach %.1f (current: %.1f) in %d hours."
                    .formatted(service, metric, forecasted, current, 24);
            case "DECREASING" -> "%s on %s trending down to %.1f. Verify service health."
                    .formatted(metric, service, forecasted);
            default -> "%s on %s is stable at %.1f.".formatted(metric, service, current);
        };
    }
}
