package com.huawei.cloud.sre.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void handleMcpToolException_invalidParam_returns400() {
        var ex = McpToolException.invalidParam("参数非法");
        ProblemDetail detail = handler.handleMcpToolException(ex);
        assertThat(detail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(detail.getDetail()).isEqualTo("参数非法");
    }

    @Test
    void handleMcpToolException_notFound_returns404() {
        var ex = McpToolException.notFound("资源不存在");
        ProblemDetail detail = handler.handleMcpToolException(ex);
        assertThat(detail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void handleMcpToolException_permissionDenied_returns403() {
        var ex = McpToolException.permissionDenied("禁止访问");
        ProblemDetail detail = handler.handleMcpToolException(ex);
        assertThat(detail.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void handleMcpToolException_internalError_returns500() {
        var ex = McpToolException.internal("内部错误", null);
        ProblemDetail detail = handler.handleMcpToolException(ex);
        assertThat(detail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @Test
    void handleHuaweiCloudException_returns502() {
        var ex = new HuaweiCloudException("AOM", "API 失败", 500, "AOM.Error", "req-abc", null);
        ProblemDetail detail = handler.handleHuaweiCloudException(ex);
        assertThat(detail.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(detail.getProperties()).containsEntry("service", "AOM");
        assertThat(detail.getProperties()).containsEntry("errorCode", "AOM.Error");
    }

    @Test
    void handleIllegalArgument_returns400() {
        var ex = new IllegalArgumentException("非法参数");
        ProblemDetail detail = handler.handleIllegalArgument(ex);
        assertThat(detail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(detail.getDetail()).isEqualTo("非法参数");
    }

    @Test
    void handleGenericException_returns500_masksMessage() {
        var ex = new RuntimeException("敏感内部错误");
        ProblemDetail detail = handler.handleGenericException(ex);
        assertThat(detail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(detail.getDetail()).doesNotContain("敏感内部错误");
    }
}
