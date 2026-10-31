package com.example.na.payment.domain;

/**
 * 支付单状态。
 *
 * <p>状态机（括号内为认领该状态的 CAS 前置状态）：
 * <pre>
 *   CREATED ──认领──> PROCESSING ──> SUCCEEDED
 *      │                   └──────> FAILED（渠道明确失败）
 *      └──认领──> CLOSING ────────> FAILED
 *
 *   SUCCEEDED ──认领──> REFUNDING ──> REFUNDED
 * </pre>
 *
 * <p>中间态（PROCESSING / REFUNDING / CLOSING）表示「已认领、渠道调用进行中或结果未知」。
 * 认领通过条件 UPDATE 完成，并发请求只有一方能抢到，另一方直接返回，
 * 从而保证渠道不会被并发调用（KI-02）。
 */
public enum PaymentStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    REFUNDING,
    REFUNDED,
    CLOSING
}
