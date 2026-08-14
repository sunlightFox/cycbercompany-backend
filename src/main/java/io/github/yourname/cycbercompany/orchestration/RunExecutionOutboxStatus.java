package io.github.yourname.cycbercompany.orchestration;

/** 事务 outbox 消息的投递状态。 */
public enum RunExecutionOutboxStatus {
    PENDING,
    DISPATCHING,
    PROCESSED,
    FAILED
}
