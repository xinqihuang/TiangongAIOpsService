package com.huawei.cloud.sre.common.exception;

/**
 * MCP Tool 业务异常。
 *
 * <p>用于 Tool 方法内部参数校验失败、依赖资源不存在、业务规则违反等场景。
 * 与 {@link HuaweiCloudException}（外部 SDK 调用异常）严格区分。
 *
 * <p>{@link GlobalExceptionHandler} 会将此异常映射为 MCP 协议的标准错误响应。
 */
public class McpToolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务错误码，例如 {@code TOOL_PARAM_INVALID}、{@code RESOURCE_NOT_FOUND}、{@code PERMISSION_DENIED}。 */
    private final String code;

    public McpToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public McpToolException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 参数非法。 */
    public static McpToolException invalidParam(String message) {
        return new McpToolException("TOOL_PARAM_INVALID", message);
    }

    /** 资源不存在。 */
    public static McpToolException notFound(String message) {
        return new McpToolException("RESOURCE_NOT_FOUND", message);
    }

    /** 权限不足。 */
    public static McpToolException permissionDenied(String message) {
        return new McpToolException("PERMISSION_DENIED", message);
    }

    /** 内部错误。 */
    public static McpToolException internal(String message, Throwable cause) {
        return new McpToolException("INTERNAL_ERROR", message, cause);
    }
}
