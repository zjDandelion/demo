package com.example.na.order.ordering.repository;

import com.example.na.order.ordering.domain.CustomerOrder;
import com.example.na.order.ordering.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    Page<CustomerOrder> findByUserId(Long userId, Pageable pageable);

    Page<CustomerOrder> findByUserIdAndStatus(Long userId, OrderStatus status, Pageable pageable);

    Optional<CustomerOrder> findByOrderNoAndUserId(String orderNo, Long userId);

    Optional<CustomerOrder> findByOrderNo(String orderNo);

    boolean existsByOrderNoAndUserId(String orderNo, Long userId);

    long countByUserId(Long userId);

    long countByStatus(OrderStatus status);

    @Modifying(flushAutomatically = true)
    @Query("update CustomerOrder o set o.status = :paidStatus, o.paidAt = :paidAt "
            + "where o.orderNo = :orderNo and o.status = :pendingStatus")
    int markPaid(@Param("orderNo") String orderNo,
                 @Param("paidStatus") OrderStatus paidStatus,
                 @Param("pendingStatus") OrderStatus pendingStatus,
                 @Param("paidAt") LocalDateTime paidAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CustomerOrder o set o.status = :cancelledStatus, o.cancelledAt = :cancelledAt "
            + "where o.orderNo = :orderNo and o.userId = :userId and o.status = :pendingStatus")
    int cancel(@Param("orderNo") String orderNo,
               @Param("userId") Long userId,
               @Param("cancelledStatus") OrderStatus cancelledStatus,
               @Param("pendingStatus") OrderStatus pendingStatus,
               @Param("cancelledAt") LocalDateTime cancelledAt);

    interface PayableOrderView {

        String getOrderNo();

        Long getUserId();

        BigDecimal getTotalAmount();

        OrderStatus getStatus();
    }
}
