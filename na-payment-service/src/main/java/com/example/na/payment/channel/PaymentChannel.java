package com.example.na.payment.channel;

import com.example.na.payment.domain.Payment;

/**
 * 支付渠道。
 *
 * <p><b>幂等要求（KI-01）</b>：真实渠道必须把 {@code idempotencyKey} 透传给渠道方，
 * 保证「同一笔支付/退款/关闭」在超时重试时不会被重复执行。
 * 本系统统一用 {@code paymentNo} 作为幂等键，退款/关闭复用同一 key，
 * 因为一个支付单在生命周期内只会退款一次、关闭一次。
 *
 * <p>渠道实现**不得**修改支付单状态，状态流转由 {@code PaymentService} 用 CAS 完成。
 */
public interface PaymentChannel {

    /**
     * 扣款。
     *
     * @param payment        支付单（只读）
     * @param idempotencyKey 幂等键，必须透传给渠道
     */
    void charge(Payment payment, String idempotencyKey);

    /**
     * 退款。
     *
     * @param payment        支付单（只读）
     * @param reason         退款原因
     * @param idempotencyKey 幂等键，必须透传给渠道
     */
    void refund(Payment payment, String reason, String idempotencyKey);

    /**
     * 关闭（撤销未扣款的支付单）。
     *
     * @param payment        支付单（只读）
     * @param reason         关闭原因
     * @param idempotencyKey 幂等键，必须透传给渠道
     */
    void close(Payment payment, String reason, String idempotencyKey);
}
