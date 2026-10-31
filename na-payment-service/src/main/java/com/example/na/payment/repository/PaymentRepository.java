package com.example.na.payment.repository;

import com.example.na.payment.domain.Payment;
import com.example.na.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 支付单仓储。
 *
 * <p>所有状态流转都通过**条件 UPDATE** 完成（CAS），返回受影响行数：
 * 1 表示本次调用抢到了状态认领权，0 表示已被其他请求认领或前置状态不符。
 * 这样并发场景下渠道只会被调用一次（KI-01 / KI-02）。
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentNo(String paymentNo);

    Optional<Payment> findByOrderNo(String orderNo);

    Optional<Payment> findByPaymentNoAndUserId(String paymentNo, Long userId);

    Optional<Payment> findByOrderNoAndUserId(String orderNo, Long userId);

    long countByOrderNo(String orderNo);

    /**
     * 通用状态认领：仅当当前状态等于 {@code from} 时才改为 {@code to}。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Payment p set p.status = :to where p.paymentNo = :paymentNo and p.status = :from")
    int claimStatus(@Param("paymentNo") String paymentNo,
                    @Param("from") PaymentStatus from,
                    @Param("to") PaymentStatus to);

    /**
     * 认领支付：CREATED -> PROCESSING，同时记录渠道幂等键（用 paymentNo）。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Payment p set p.status = :processingStatus, p.channelAttemptAt = :attemptAt "
            + "where p.paymentNo = :paymentNo and p.status = :createdStatus")
    int claimForCharge(@Param("paymentNo") String paymentNo,
                       @Param("processingStatus") PaymentStatus processingStatus,
                       @Param("createdStatus") PaymentStatus createdStatus,
                       @Param("attemptAt") LocalDateTime attemptAt);

    /**
     * 认领退款：SUCCEEDED -> REFUNDING。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Payment p set p.status = :refundingStatus, p.channelAttemptAt = :attemptAt "
            + "where p.paymentNo = :paymentNo and p.status = :succeededStatus")
    int claimForRefund(@Param("paymentNo") String paymentNo,
                       @Param("refundingStatus") PaymentStatus refundingStatus,
                       @Param("succeededStatus") PaymentStatus succeededStatus,
                       @Param("attemptAt") LocalDateTime attemptAt);

    /**
     * 认领关闭：CREATED -> CLOSING。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Payment p set p.status = :closingStatus, p.channelAttemptAt = :attemptAt "
            + "where p.paymentNo = :paymentNo and p.status = :createdStatus")
    int claimForClose(@Param("paymentNo") String paymentNo,
                      @Param("closingStatus") PaymentStatus closingStatus,
                      @Param("createdStatus") PaymentStatus createdStatus,
                      @Param("attemptAt") LocalDateTime attemptAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Payment p set p.status = :succeededStatus, p.succeededAt = :succeededAt "
            + "where p.paymentNo = :paymentNo and p.status = :processingStatus")
    int markSucceeded(@Param("paymentNo") String paymentNo,
                      @Param("succeededStatus") PaymentStatus succeededStatus,
                      @Param("processingStatus") PaymentStatus processingStatus,
                      @Param("succeededAt") LocalDateTime succeededAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Payment p set p.status = :refundedStatus, p.refundedAt = :refundedAt, p.refundReason = :reason "
            + "where p.paymentNo = :paymentNo and p.status = :refundingStatus")
    int markRefunded(@Param("paymentNo") String paymentNo,
                     @Param("refundedStatus") PaymentStatus refundedStatus,
                     @Param("refundingStatus") PaymentStatus refundingStatus,
                     @Param("refundedAt") LocalDateTime refundedAt,
                     @Param("reason") String reason);

    /**
     * 标记失败：可从 PROCESSING（渠道失败）或 CLOSING（关闭完成）进入。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Payment p set p.status = :failedStatus "
            + "where p.paymentNo = :paymentNo and p.status in :fromStatuses")
    int markFailed(@Param("paymentNo") String paymentNo,
                   @Param("failedStatus") PaymentStatus failedStatus,
                   @Param("fromStatuses") java.util.Collection<PaymentStatus> fromStatuses);
}
