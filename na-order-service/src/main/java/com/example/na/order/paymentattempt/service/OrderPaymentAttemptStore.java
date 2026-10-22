package com.example.na.order.paymentattempt.service;

import com.example.na.order.ordering.domain.CustomerOrder;
import com.example.na.order.ordering.domain.OrderStatus;
import com.example.na.order.ordering.repository.OrderRepository;
import com.example.na.order.paymentattempt.domain.OrderPaymentAttempt;
import com.example.na.order.paymentattempt.domain.OrderPaymentAttemptStatus;
import com.example.na.order.paymentattempt.repository.OrderPaymentAttemptRepository;
import com.example.na.order.shared.BusinessException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderPaymentAttemptStore {

    private static final List<OrderPaymentAttemptStatus> STARTED_ONLY =
            Collections.singletonList(OrderPaymentAttemptStatus.STARTED);
    private static final List<OrderPaymentAttemptStatus> FAILED_ONLY =
            Collections.singletonList(OrderPaymentAttemptStatus.FAILED);
    private static final List<OrderPaymentAttemptStatus> NOT_REFUNDED_OR_FAILED = Arrays.asList(
            OrderPaymentAttemptStatus.STARTED,
            OrderPaymentAttemptStatus.SUCCEEDED,
            OrderPaymentAttemptStatus.REFUND_PENDING);
    private static final List<OrderPaymentAttemptStatus> CHARGEABLE = Arrays.asList(
            OrderPaymentAttemptStatus.STARTED,
            OrderPaymentAttemptStatus.SUCCEEDED);

    private final OrderPaymentAttemptRepository attemptRepository;
    private final OrderRepository orderRepository;
    private final PlatformTransactionManager transactionManager;

    public StartResult start(String orderNo, Long userId, BigDecimal amount) {
        Optional<OrderPaymentAttempt> existing = attemptRepository.findByOrderNo(orderNo);
        if (existing.isPresent()) {
            return resume(existing.get());
        }
        try {
            StartResult created = transactionTemplate().execute(status -> {
                OrderPaymentAttempt attempt = new OrderPaymentAttempt(orderNo, userId, amount,
                        OrderPaymentAttemptStatus.STARTED);
                attempt.setAttemptCount(1);
                attemptRepository.saveAndFlush(attempt);
                return new StartResult(attempt, true);
            });
            if (created == null) {
                throw BusinessException.conflict("支付尝试创建失败");
            }
            return created;
        } catch (DataIntegrityViolationException exception) {
            OrderPaymentAttempt raced = attemptRepository.findByOrderNo(orderNo)
                    .orElseThrow(() -> BusinessException.conflict("支付尝试正在创建，请稍后重试"));
            return resume(raced);
        }
    }

    public Completion completePaid(String orderNo, String paymentNo) {
        Completion completion = transactionTemplate().execute(status -> {
            int updated = orderRepository.markPaid(orderNo, OrderStatus.PAID,
                    OrderStatus.PENDING_PAYMENT, LocalDateTime.now());
            CustomerOrder order = orderRepository.findByOrderNo(orderNo).orElse(null);
            if (order == null) {
                return Completion.UNKNOWN;
            }
            if (updated == 1) {
                markSucceeded(orderNo, paymentNo);
                return Completion.PAID;
            }
            if (order.getStatus() == OrderStatus.PAID) {
                markSucceeded(orderNo, paymentNo);
                return Completion.ALREADY_PAID;
            }
            if (order.getStatus() == OrderStatus.CANCELLED) {
                markRefundPending(orderNo, paymentNo);
                return Completion.ORDER_CANCELLED;
            }
            return Completion.UNKNOWN;
        });
        return completion == null ? Completion.UNKNOWN : completion;
    }

    public void markSucceeded(String orderNo, String paymentNo) {
        transactionTemplate().executeWithoutResult(status ->
                attemptRepository.transitionWithPaymentNo(orderNo, STARTED_ONLY,
                        OrderPaymentAttemptStatus.SUCCEEDED, paymentNo, LocalDateTime.now()));
    }

    public void markRefundPending(String orderNo, String paymentNo) {
        transactionTemplate().executeWithoutResult(status ->
                attemptRepository.transitionWithPaymentNo(orderNo, CHARGEABLE,
                        OrderPaymentAttemptStatus.REFUND_PENDING, paymentNo, LocalDateTime.now()));
    }

    public void markRefunded(String orderNo) {
        transactionTemplate().executeWithoutResult(status ->
                attemptRepository.transition(orderNo, NOT_REFUNDED_OR_FAILED,
                        OrderPaymentAttemptStatus.REFUNDED, LocalDateTime.now()));
    }

    public void markFailed(String orderNo, String error) {
        transactionTemplate().executeWithoutResult(status ->
                attemptRepository.transitionWithError(orderNo, STARTED_ONLY,
                        OrderPaymentAttemptStatus.FAILED, truncate(error), LocalDateTime.now()));
    }

    public void touch(String orderNo, String error) {
        transactionTemplate().executeWithoutResult(status ->
                attemptRepository.touch(orderNo, truncate(error), LocalDateTime.now()));
    }

    private StartResult resume(OrderPaymentAttempt attempt) {
        if (attempt.getStatus() == OrderPaymentAttemptStatus.FAILED) {
            final String orderNo = attempt.getOrderNo();
            transactionTemplate().executeWithoutResult(status -> attemptRepository.transition(
                    orderNo, FAILED_ONLY, OrderPaymentAttemptStatus.STARTED, LocalDateTime.now()));
            attempt = attemptRepository.findByOrderNo(orderNo).orElse(attempt);
        }
        return new StartResult(attempt, false);
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    @Getter
    public static class StartResult {

        private final OrderPaymentAttempt attempt;

        private final boolean created;

        StartResult(OrderPaymentAttempt attempt, boolean created) {
            this.attempt = attempt;
            this.created = created;
        }
    }

    public enum Completion {
        PAID,
        ALREADY_PAID,
        ORDER_CANCELLED,
        UNKNOWN
    }
}
