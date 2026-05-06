package com.huawei.cloud.sre.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HuaweiCloudExceptionTest {

    @Test
    void constructor_twoArgs_setsServiceAndMessage() {
        var ex = new HuaweiCloudException("AOM", "查询失败");
        assertThat(ex.getService()).isEqualTo("AOM");
        assertThat(ex.getMessage()).isEqualTo("查询失败");
        assertThat(ex.getHttpStatus()).isZero();
        assertThat(ex.getErrorCode()).isNull();
        assertThat(ex.getRequestId()).isNull();
    }

    @Test
    void constructor_withCause_wrapsException() {
        var cause = new RuntimeException("原始异常");
        var ex = new HuaweiCloudException("LTS", "日志查询失败", cause);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getService()).isEqualTo("LTS");
    }

    @Test
    void constructor_fullArgs_setsAllFields() {
        var ex = new HuaweiCloudException("KMS", "解密失败", 403, "KMS.Unauthorized", "req-123", null);
        assertThat(ex.getHttpStatus()).isEqualTo(403);
        assertThat(ex.getErrorCode()).isEqualTo("KMS.Unauthorized");
        assertThat(ex.getRequestId()).isEqualTo("req-123");
    }
}
