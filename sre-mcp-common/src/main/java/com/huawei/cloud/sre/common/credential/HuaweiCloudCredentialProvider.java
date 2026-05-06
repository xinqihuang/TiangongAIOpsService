package com.huawei.cloud.sre.common.credential;

import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.core.auth.ICredential;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 华为云凭证提供者。
 *
 * <p>支持两种模式：
 * <ul>
 *   <li><b>明文模式</b>（dev/staging）：直接从配置读取 AK/SK</li>
 *   <li><b>KMS 模式</b>（prod）：从配置读取密文，通过 {@link KmsDecryptor} 解密，
 *       并每 6 小时定时刷新（避免密钥轮换后需要重启）</li>
 * </ul>
 */
@Component
public class HuaweiCloudCredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(HuaweiCloudCredentialProvider.class);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile ICredential credentials;

    private final String projectId;
    private final boolean kmsEnabled;
    private final String rawAk;
    private final String rawSk;
    private final String kmsKeyId;
    private final KmsDecryptor kmsDecryptor;

    /**
     * @param ak           明文 AK 或 KMS 密文 AK
     * @param sk           明文 SK 或 KMS 密文 SK
     * @param projectId    华为云 Project ID
     * @param kmsEnabled   是否启用 KMS 解密
     * @param kmsKeyId     KMS 主密钥 ID（kmsEnabled=true 时必填）
     * @param kmsDecryptor KMS 解密器
     */
    public HuaweiCloudCredentialProvider(
            @Value("${huaweicloud.ak}") String ak,
            @Value("${huaweicloud.sk}") String sk,
            @Value("${huaweicloud.project-id}") String projectId,
            @Value("${huaweicloud.kms.enabled:false}") boolean kmsEnabled,
            @Value("${huaweicloud.kms.key-id:}") String kmsKeyId,
            @Autowired(required = false) @Nullable KmsDecryptor kmsDecryptor
    ) {
        this.rawAk = ak;
        this.rawSk = sk;
        this.projectId = projectId;
        this.kmsEnabled = kmsEnabled;
        this.kmsKeyId = kmsKeyId;
        this.kmsDecryptor = kmsDecryptor;
        refresh();
    }

    /**
     * 获取当前有效的华为云凭证。
     *
     * @return 当前凭证（线程安全）
     */
    public ICredential getCredentials() {
        lock.readLock().lock();
        try {
            return credentials;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 每 6 小时自动刷新一次凭证（生产 KMS 模式下有效）。 */
    @Scheduled(fixedRateString = "${huaweicloud.credential-refresh-interval-ms:21600000}")
    public void refresh() {
        lock.writeLock().lock();
        try {
            String ak;
            String sk;
            if (kmsEnabled) {
                log.info("Refreshing Huawei Cloud credentials via KMS, keyId={}", kmsKeyId);
                ak = kmsDecryptor.decrypt(rawAk, kmsKeyId);
                sk = kmsDecryptor.decrypt(rawSk, kmsKeyId);
            } else {
                ak = rawAk;
                sk = rawSk;
            }
            credentials = new BasicCredentials()
                    .withAk(ak)
                    .withSk(sk)
                    .withProjectId(projectId);
            log.info("Huawei Cloud credentials refreshed successfully");
        } finally {
            lock.writeLock().unlock();
        }
    }
}
