package com.example.na.order.paymentattempt.repository;

import com.example.na.order.paymentattempt.domain.OrderPaymentAttempt;
import com.example.na.order.paymentattempt.domain.OrderPaymentAttemptStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderPaymentAttemptRepository extends JpaRepository<OrderPaymentAttempt, Long> {

    Optional<OrderPaymentAttempt> findByOrderNo(String orderNo);

    List<OrderPaymentAttempt> findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<OrderPaymentAttemptStatus> statuses, LocalDateTime cutoff, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderPaymentAttempt a set a.status = :nextStatus, a.lastError = null, "
            + "a.attemptCount = a.attemptCount + 1, a.updatedAt = :now "
            + "where a.orderNo = :orderNo and a.status in :expectedStatuses")
    int transition(@Param("orderNo") String orderNo,
                   @Param("expectedStatuses") Collection<OrderPaymentAttemptStatus> expectedStatuses,
                   @Param("nextStatus") OrderPaymentAttemptStatus nextStatus,
                   @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderPaymentAttempt a set a.status = :nextStatus, a.paymentNo = :paymentNo, "
            + "a.lastError = null, a.attemptCount = a.attemptCount + 1, a.updatedAt = :now "
            + "where a.orderNo = :orderNo and a.status in :expectedStatuses")
    int transitionWithPaymentNo(@Param("orderNo") String orderNo,
                                @Param("expectedStatuses") Collection<OrderPaymentAttemptStatus> expectedStatuses,
                                @Param("nextStatus") OrderPaymentAttemptStatus nextStatus,
                                @Param("paymentNo") String paymentNo,
                                @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderPaymentAttempt a set a.status = :nextStatus, a.lastError = :lastError, "
            + "a.attemptCount = a.attemptCount + 1, a.updatedAt = :now "
            + "where a.orderNo = :orderNo and a.status in :expectedStatuses")
    int transitionWithError(@Param("orderNo") String orderNo,
                            @Param("expectedStatuses") Collection<OrderPaymentAttemptStatus> expectedStatuses,
                            @Param("nextStatus") OrderPaymentAttemptStatus nextStatus,
                            @Param("lastError") String lastError,
                            @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OrderPaymentAttempt a set a.lastError = :lastError, "
            + "a.attemptCount = a.attemptCount + 1, a.updatedAt = :now where a.orderNo = :orderNo")
    int touch(@Param("orderNo") String orderNo,
              @Param("lastError") String lastError,
              @Param("now") LocalDateTime now);
}
