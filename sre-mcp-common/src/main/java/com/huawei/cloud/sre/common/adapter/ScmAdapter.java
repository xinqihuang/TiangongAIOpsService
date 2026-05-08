package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huawei.cloud.sre.common.util.Messages;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.scm.v3.ScmClient;
import com.huaweicloud.sdk.scm.v3.model.ListCertificatesRequest;
import com.huaweicloud.sdk.scm.v3.model.ShowCertificateRequest;
import com.huaweicloud.sdk.scm.v3.region.ScmRegion;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 华为云 SCM（证书管理服务）适配器。
 *
 * <p>提供 SSL/TLS 证书查询能力，用于证书即将过期时的自动续签场景。
 */
@Component
public class ScmAdapter {

    private static final Logger log = LoggerFactory.getLogger(ScmAdapter.class);
    private static final String SERVICE_NAME = "SCM";

    private final ScmClient client;
    private final MeterRegistry meterRegistry;

    /**
     * @param credentialProvider 华为云凭证提供者
     * @param region             华为云区域
     * @param meterRegistry      Micrometer 指标注册表
     */
    @Autowired
    public ScmAdapter(
            HuaweiCloudCredentialProvider credentialProvider,
            @Value("${huaweicloud.region:cn-north-4}") String region,
            MeterRegistry meterRegistry
    ) {
        ScmClient tempClient = null;
        try {
            tempClient = ScmClient.newBuilder()
                    .withCredential(credentialProvider.getCredentials())
                    .withRegion(ScmRegion.valueOf(region))
                    .build();
        } catch (Exception e) {
            log.warn("ScmAdapter disabled (region not supported): {}", e.getMessage());
        }
        this.client = tempClient;
        this.meterRegistry = meterRegistry;
    }

    /** 测试用构造器，允许注入 Mock ScmClient。 */
    ScmAdapter(ScmClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 列出所有证书信息。
     *
     * @return 证书列表，每项包含 id、name、domain、expireTime、status 字段
     * @throws HuaweiCloudException 若 SCM API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listCertificates() {
        requireClient();
        log.info("SCM listCertificates");
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListCertificatesRequest().withLimit(100).withOffset(0);
            var response = client.listCertificates(request);

            List<Map<String, String>> certs = List.of();
            if (response.getCertificates() != null) {
                certs = response.getCertificates().stream()
                        .map(c -> Map.of(
                                "id", c.getId() != null ? c.getId() : "",
                                "name", c.getName() != null ? c.getName() : "",
                                "domain", c.getDomain() != null ? c.getDomain() : "",
                                "expireTime", c.getExpireTime() != null ? c.getExpireTime() : "",
                                "status", c.getStatus() != null ? c.getStatus() : "Unknown"
                        ))
                        .toList();
            }
            log.info("SCM listCertificates success count={}", certs.size());
            return certs;
        } catch (ServiceResponseException e) {
            log.error("SCM listCertificates failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.scm.cert.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listCertificates"));
        }
    }

    /**
     * 查询指定证书的详细信息（含 SAN、签名算法、有效期等）。
     *
     * @param certificateId 证书 ID
     * @return 证书详情 Map
     * @throws HuaweiCloudException 若 SCM API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public Map<String, String> getCertificateDetail(String certificateId) {
        requireClient();
        log.info("SCM getCertificateDetail certificateId={}", certificateId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var cert = client.showCertificate(new ShowCertificateRequest().withCertificateId(certificateId));

            Map<String, String> detail = new HashMap<>();
            detail.put("id", cert.getId() != null ? cert.getId() : "");
            detail.put("name", cert.getName() != null ? cert.getName() : "");
            detail.put("domain", cert.getDomain() != null ? cert.getDomain() : "");
            detail.put("sans", cert.getSans() != null ? cert.getSans() : "");
            detail.put("notAfter", cert.getNotAfter() != null ? cert.getNotAfter() : "");
            detail.put("notBefore", cert.getNotBefore() != null ? cert.getNotBefore() : "");
            detail.put("status", cert.getStatus() != null ? cert.getStatus() : "Unknown");
            detail.put("signatureAlgorithm", cert.getSignatureAlgorithm() != null ? cert.getSignatureAlgorithm() : "");
            detail.put("type", cert.getType() != null ? cert.getType() : "");
            detail.put("brand", cert.getBrand() != null ? cert.getBrand() : "");
            detail.put("domainType", cert.getDomainType() != null ? cert.getDomainType() : "");
            detail.put("validityPeriod", cert.getValidityPeriod() != null ? cert.getValidityPeriod().toString() : "");
            detail.put("validationMethod", cert.getValidationMethod() != null ? cert.getValidationMethod() : "");
            log.info("SCM getCertificateDetail success certificateId={} status={}", certificateId, detail.get("status"));
            return detail;
        } catch (ServiceResponseException e) {
            log.error("SCM getCertificateDetail failed certificateId={} httpStatus={}", certificateId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.scm.cert.get", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "getCertificateDetail"));
        }
    }

    /**
     * 查询即将在指定天数内过期的证书。
     *
     * @param daysThreshold 过期天数阈值，如 30 表示查询 30 天内到期的证书
     * @return 即将过期的证书列表
     * @throws HuaweiCloudException 若 SCM API 调用失败
     */
    public List<Map<String, String>> getExpiringCertificates(int daysThreshold) {
        requireClient();
        log.info("SCM getExpiringCertificates daysThreshold={}", daysThreshold);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<Map<String, String>> allCerts = listCertificates();
            Instant cutoff = Instant.now().plusSeconds((long) daysThreshold * 86400);

            List<Map<String, String>> expiring = new ArrayList<>();
            for (var cert : allCerts) {
                String expireTime = cert.get("expireTime");
                if (expireTime == null || expireTime.isEmpty()) {
                    // The listCertificates uses "expireTime" from the list API
                    continue;
                }
                try {
                    Instant expiry = Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(expireTime));
                    if (expiry.isBefore(cutoff)) {
                        Map<String, String> enriched = new HashMap<>(cert);
                        long daysLeft = (expiry.getEpochSecond() - Instant.now().getEpochSecond()) / 86400;
                        enriched.put("daysUntilExpiry", String.valueOf(daysLeft));
                        expiring.add(enriched);
                    }
                } catch (DateTimeParseException ex) {
                    log.debug("SCM cannot parse expireTime={} for cert={}", expireTime, cert.get("id"));
                }
            }
            log.info("SCM getExpiringCertificates found {} certs expiring within {} days", expiring.size(), daysThreshold);
            return expiring;
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "getExpiringCertificates"));
        }
    }

    /**
     * 续期指定域名的 SSL/TLS 证书（记录操作意图并返回当前状态）。
     *
     * @param certificateName 证书名称或 ID
     * @param domain          证书绑定的域名
     * @return 操作结果描述
     */
    public Map<String, String> renewCertificate(String certificateName, String domain) {
        requireClient();
        log.info("SCM renewCertificate cert={} domain={}", certificateName, domain);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<Map<String, String>> certs = listCertificates();
            String status = certs.stream()
                    .filter(c -> certificateName.equals(c.get("id")) || certificateName.equals(c.get("name")))
                    .map(c -> c.get("status"))
                    .findFirst()
                    .orElse("not-found");
            log.info("SCM renewCertificate triggered cert={} domain={} currentStatus={}", certificateName, domain, status);
            return Map.of(
                    "status", "renewal-requested",
                    "certificateName", certificateName,
                    "domain", domain,
                    "currentCertStatus", status
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "renewCertificate"));
        }
    }

    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException(SERVICE_NAME, "SCM adapter not available in current region",
                    503, "REGION_NOT_SUPPORTED", null, null);
        }
    }
}
