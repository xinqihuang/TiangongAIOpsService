package com.huawei.cloud.sre.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * 全局异常处理器，将业务异常统一映射为 RFC 7807 Problem Detail 格式的 HTTP 响应。
 *
 * <p>MCP 协议层通过 HTTP 传输，使用标准 Problem Detail 可被 MCP Inspector 和 Client 解析。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_TYPE_BASE = "https://sre-mcp.huaweicloud.com/errors/";

    /**
     * 处理 MCP Tool 业务异常。
     *
     * @param ex McpToolException 实例
     * @return 对应 HTTP 状态码的 ProblemDetail
     */
    @ExceptionHandler(McpToolException.class)
    public ProblemDetail handleMcpToolException(McpToolException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "TOOL_PARAM_INVALID" -> HttpStatus.BAD_REQUEST;
            case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        log.error("McpToolException code={} message={}", ex.getCode(), ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setType(URI.create(ERROR_TYPE_BASE + ex.getCode().toLowerCase().replace('_', '-')));
        problem.setTitle(ex.getCode());
        return problem;
    }

    /**
     * 处理华为云 SDK 调用异常。
     *
     * @param ex HuaweiCloudException 实例
     * @return HTTP 502 Bad Gateway ProblemDetail
     */
    @ExceptionHandler(HuaweiCloudException.class)
    public ProblemDetail handleHuaweiCloudException(HuaweiCloudException ex) {
        log.error("HuaweiCloudException service={} httpStatus={} errorCode={} requestId={} message={}",
                ex.getService(), ex.getHttpStatus(), ex.getErrorCode(), ex.getRequestId(), ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY,
                String.format("华为云 %s 服务调用失败: %s", ex.getService(), ex.getMessage())
        );
        problem.setType(URI.create(ERROR_TYPE_BASE + "huaweicloud-api-error"));
        problem.setTitle("HuaweiCloud API Error");
        problem.setProperty("service", ex.getService());
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("requestId", ex.getRequestId());
        return problem;
    }

    /**
     * 处理参数校验失败（Bean Validation）。
     *
     * @param ex IllegalArgumentException 实例
     * @return HTTP 400 ProblemDetail
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("IllegalArgumentException: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create(ERROR_TYPE_BASE + "invalid-argument"));
        problem.setTitle("Invalid Argument");
        return problem;
    }

    /**
     * 兜底处理未预期异常，避免内部细节泄露。
     *
     * @param ex 任意未被上述处理器捕获的异常
     * @return HTTP 500 ProblemDetail（屏蔽内部错误信息）
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        log.error("Unexpected exception: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "服务内部错误，请稍后重试"
        );
        problem.setType(URI.create(ERROR_TYPE_BASE + "internal-error"));
        problem.setTitle("Internal Server Error");
        return problem;
    }
}
