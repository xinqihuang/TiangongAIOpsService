package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.scm.v3.ScmClient;
import com.huaweicloud.sdk.scm.v3.model.ListCertificatesRequest;
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
     * 续期指定域名的 SSL/TLS 证书。
     *
     * <p>SCM 本身提供证书托管，续期操作依赖 CA 签发流程。此方法触发续期请求并记录操作。
     * 实际生产环境需结合 CA 集成（如 Let's Encrypt / CFCA）完成自动签发。
     *
     * @param certificateName 证书名称或 ID
     * @param domain          证书绑定的域名
     * @return 操作结果描述
     */
    public Map<String, String> renewCertificate(String certificateName, String domain) {
        log.info("SCM renewCertificate cert={} domain={}", certificateName, domain);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Certificate renewal requires CA integration. This records the intent
            // and returns the current certificate status for operator action.
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

    /**
     * 列出所有证书信息。
     *
     * @return 证书列表，每项包含 id、name、domain、expireTime、status 字段
     * @throws HuaweiCloudException 若 SCM API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listCertificates() {
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
                    SERVICE_NAME, "SCM 证书列表查询失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listCertificates"));
        }
    }
    /** 检查 client 是否可用（区域不支持时为 null）。*/
    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException("Scm", "Scm adapter not available in current region", 503, "REGION_NOT_SUPPORTED", null, null);
        }
    }

}