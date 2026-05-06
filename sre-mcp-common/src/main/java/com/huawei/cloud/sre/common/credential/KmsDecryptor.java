package com.huawei.cloud.sre.common.credential;

import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.kms.v2.KmsClient;
import com.huaweicloud.sdk.kms.v2.model.DecryptDataRequest;
import com.huaweicloud.sdk.kms.v2.model.DecryptDataRequestBody;
import com.huaweicloud.sdk.kms.v2.region.KmsRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 华为云 KMS（密钥管理服务）解密器。
 *
 * <p>用于在生产环境中解密经 KMS 加密的 AK/SK 密文，避免明文凭证出现在配置中。
 * 仅当 {@code huaweicloud.kms.enabled=true} 时才创建此 Bean，本地开发时不加载。
 */
@Component
@ConditionalOnProperty(name = "huaweicloud.kms.enabled", havingValue = "true")
public class KmsDecryptor {

    private static final Logger log = LoggerFactory.getLogger(KmsDecryptor.class);

    private final KmsClient kmsClient;

    /**
     * @param ak        华为云 Access Key（用于初始化 KMS 客户端，可为明文 bootstrap 凭证）
     * @param sk        华为云 Secret Key
     * @param projectId 华为云 Project ID
     * @param region    华为云区域，如 cn-north-4
     */
    public KmsDecryptor(
            @Value("${huaweicloud.ak}") String ak,
            @Value("${huaweicloud.sk}") String sk,
            @Value("${huaweicloud.project-id}") String projectId,
            @Value("${huaweicloud.region:cn-north-4}") String region
    ) {
        this.kmsClient = KmsClient.newBuilder()
                .withCredential(new BasicCredentials()
                        .withAk(ak)
                        .withSk(sk)
                        .withProjectId(projectId))
                .withRegion(KmsRegion.valueOf(region))
                .build();
    }

    /**
     * 解密 KMS 密文，返回明文字符串。
     *
     * @param ciphertext Base64 编码的 KMS 密文
     * @param keyId      KMS 主密钥 ID
     * @return 解密后的明文
     * @throws HuaweiCloudException 若 KMS 调用失败
     */
    public String decrypt(String ciphertext, String keyId) {
        log.info("KMS decrypt request for keyId={}", keyId);
        try {
            var request = new DecryptDataRequest()
                    .withBody(new DecryptDataRequestBody()
                            .withCipherText(ciphertext)
                            .withKeyId(keyId));
            var response = kmsClient.decryptData(request);
            return response.getPlainText();
        } catch (ServiceResponseException e) {
            throw new HuaweiCloudException(
                    "KMS",
                    "KMS 解密失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(),
                    e.getErrorCode(),
                    e.getRequestId(),
                    e
            );
        }
    }
}
