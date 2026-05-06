package com.huawei.cloud.sre.common.exception;

/**
 * 华为云 SDK 调用异常的统一封装。
 *
 * <p>所有 Adapter 在捕获 {@code com.huaweicloud.sdk.core.exception.ServiceResponseException} 后
 * 必须重新抛出此异常，使得上层（GlobalExceptionHandler / Aspect）能用统一逻辑处理。
 *
 * <p>携带华为云 SDK 返回的 HTTP 状态码与错误码，便于诊断与告警。
 */
public class HuaweiCloudException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String service;
    private final int httpStatus;
    private final String errorCode;
    private final String requestId;

    public HuaweiCloudException(String service, String message) {
        this(service, message, 0, null, null, null);
    }

    public HuaweiCloudException(String service, String message, Throwable cause) {
        this(service, message, 0, null, null, cause);
    }

    public HuaweiCloudException(String service,
                                String message,
                                int httpStatus,
                                String errorCode,
                                String requestId,
                                Throwable cause) {
        super(message, cause);
        this.service = service;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.requestId = requestId;
    }

    public String getService() {
        return service;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getRequestId() {
        return requestId;
    }
}
