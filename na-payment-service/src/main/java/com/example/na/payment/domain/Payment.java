package com.example.na.payment.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payments_payment_no", columnNames = "payment_no"),
        @UniqueConstraint(name = "uk_payments_order_no", columnNames = "order_no")
})
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_no", nullable = false, length = 32)
    private String paymentNo;

    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 20)
    private String channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "succeeded_at")
    private LocalDateTime succeededAt;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "refund_reason", length = 200)
    private String refundReason;

    /**
     * 渠道调用认领时间（KI-01/KI-02）。
     * 认领中间态时写入，用于识别「认领后长时间未收敛」的僵死支付单。
     */
    @Column(name = "channel_attempt_at")
    private LocalDateTime channelAttemptAt;

    public Payment(String paymentNo, String orderNo, Long userId, BigDecimal amount, String currency, String channel) {
        this.paymentNo = paymentNo;
        this.orderNo = orderNo;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.channel = channel;
        this.status = PaymentStatus.CREATED;
    }

    public void markSucceeded(LocalDateTime succeededAt) {
        this.status = PaymentStatus.SUCCEEDED;
        this.succeededAt = succeededAt;
    }

    public void markFailed() {
        this.status = PaymentStatus.FAILED;
    }

    public void markRefunded(LocalDateTime refundedAt, String refundReason) {
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = refundedAt;
        this.refundReason = refundReason;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
