package com.huawei.cloud.sre.remediation.statemachine;

/**
 * 非法状态转换异常。
 *
 * <p>当尝试执行状态机中未定义的转换时抛出。
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final RemediationState from;
    private final RemediationState to;

    /**
     * @param from 当前状态
     * @param to   目标状态
     */
    public IllegalStateTransitionException(RemediationState from, RemediationState to) {
        super("Illegal state transition: %s → %s".formatted(from, to));
        this.from = from;
        this.to = to;
    }

    /** 当前状态。 */
    public RemediationState getFrom() { return from; }

    /** 目标状态。 */
    public RemediationState getTo() { return to; }
}
