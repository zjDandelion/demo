package com.example.na.payment.repository;

import com.example.na.payment.domain.Payment;
import com.example.na.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentNo(String paymentNo);

    Optional<Payment> findByOrderNo(String orderNo);

    Optional<Payment> findByPaymentNoAndUserId(String paymentNo, Long userId);

    Optional<Payment> findByOrderNoAndUserId(String orderNo, Long userId);

    long countByOrderNo(String orderNo);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Payment p set p.status = :succeededStatus, p.succeededAt = :succeededAt "
            + "where p.paymentNo = :paymentNo and p.status = :createdStatus")
    int markSucceeded(@Param("paymentNo") String paymentNo,
                      @Param("succeededStatus") PaymentStatus succeededStatus,
                      @Param("createdStatus") PaymentStatus createdStatus,
                      @Param("succeededAt") LocalDateTime succeededAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Payment p set p.status = :refundedStatus, p.refundedAt = :refundedAt, p.refundReason = :reason "
            + "where p.paymentNo = :paymentNo and p.status = :succeededStatus")
    int markRefunded(@Param("paymentNo") String paymentNo,
                     @Param("refundedStatus") PaymentStatus refundedStatus,
                     @Param("succeededStatus") PaymentStatus succeededStatus,
                     @Param("refundedAt") LocalDateTime refundedAt,
                     @Param("reason") String reason);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Payment p set p.status = :failedStatus "
            + "where p.paymentNo = :paymentNo and p.status = :createdStatus")
    int markFailed(@Param("paymentNo") String paymentNo,
                   @Param("failedStatus") PaymentStatus failedStatus,
                   @Param("createdStatus") PaymentStatus createdStatus);
}
