package com.huawei.cloud.sre.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolExceptionTest {

    @Test
    void invalidParam_returnsCorrectCode() {
        var ex = McpToolException.invalidParam("参数 service 不能为空");
        assertThat(ex.getCode()).isEqualTo("TOOL_PARAM_INVALID");
        assertThat(ex.getMessage()).isEqualTo("参数 service 不能为空");
    }

    @Test
    void notFound_returnsCorrectCode() {
        var ex = McpToolException.notFound("资源不存在");
        assertThat(ex.getCode()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void permissionDenied_returnsCorrectCode() {
        var ex = McpToolException.permissionDenied("无权限");
        assertThat(ex.getCode()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void internal_wrapsThrowable() {
        var cause = new IllegalStateException("DB error");
        var ex = McpToolException.internal("内部错误", cause);
        assertThat(ex.getCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void constructor_withCause_chainsCause() {
        var cause = new RuntimeException("root");
        var ex = new McpToolException("CUSTOM_CODE", "自定义错误", cause);
        assertThat(ex.getCode()).isEqualTo("CUSTOM_CODE");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
