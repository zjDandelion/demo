package com.example.na.order.payment.reconciliation;

import com.example.na.order.ordering.domain.CustomerOrder;
import com.example.na.order.ordering.domain.OrderStatus;
import com.example.na.order.ordering.repository.OrderRepository;
import com.example.na.order.paymentattempt.domain.OrderPaymentAttempt;
import com.example.na.order.paymentattempt.domain.OrderPaymentAttemptStatus;
import com.example.na.order.paymentattempt.repository.OrderPaymentAttemptRepository;
import com.example.na.order.paymentattempt.service.OrderPaymentAttemptStore;
import com.example.na.order.paymentclient.PaymentClient;
import com.example.na.order.paymentclient.PaymentClientException;
import com.example.na.order.paymentclient.PaymentUnavailableException;
import com.example.na.payment.contract.PaymentCommand;
import com.example.na.payment.contract.PaymentStatusView;
import com.example.na.payment.contract.PaymentView;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "app.reconciliation", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class OrderPaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentReconciliationJob.class);

    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_CREATED = "CREATED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_REFUNDED = "REFUNDED";
    private static final String CURRENCY = "CNY";

    private final OrderPaymentAttemptRepository attemptRepository;
    private final OrderRepository orderRepository;
    private final OrderPaymentAttemptStore attemptStore;
    private final PaymentClient paymentClient;

    @Value("${app.reconciliation.stale-seconds:60}")
    private long staleSeconds = 60;

    @Value("${app.reconciliation.batch-size:100}")
    private int batchSize = 100;

    @Value("${app.reconciliation.alert-threshold:5}")
    private int alertThreshold = 5;

    @Scheduled(fixedDelayString = "${app.reconciliation.delay-ms:60000}",
            initialDelayString = "${app.reconciliation.initial-delay-ms:60000}")
    public void reconcile() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(staleSeconds);
        List<OrderPaymentAttempt> attempts =
                attemptRepository.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                        Arrays.asList(OrderPaymentAttemptStatus.STARTED,
                                OrderPaymentAttemptStatus.REFUND_PENDING),
                        cutoff, PageRequest.of(0, batchSize));
        if (attempts.isEmpty()) {
            return;
        }
        log.info("Payment reconciliation started: candidates={}", attempts.size());
        for (OrderPaymentAttempt attempt : attempts) {
            try {
                if (attempt.getStatus() == OrderPaymentAttemptStatus.STARTED) {
                    reconcileStarted(attempt);
                } else {
                    reconcileRefundPending(attempt);
                }
            } catch (RuntimeException exception) {
                log.warn("Payment reconciliation failed: orderNo={}", attempt.getOrderNo(), exception);
                fail(attempt, "对账处理异常");
            }
        }
    }

    private void reconcileStarted(OrderPaymentAttempt attempt) {
        Optional<CustomerOrder> orderOptional = orderRepository.findByOrderNo(attempt.getOrderNo());
        if (!orderOptional.isPresent()) {
            attemptStore.markFailed(attempt.getOrderNo(), "订单不存在");
            return;
        }
        CustomerOrder order = orderOptional.get();

        Optional<PaymentStatusView> paymentOptional;
        try {
            paymentOptional = paymentClient.findByOrder(attempt.getUserId(), attempt.getOrderNo());
        } catch (PaymentUnavailableException exception) {
            fail(attempt, exception.getMessage());
            return;
        }
        if (!paymentOptional.isPresent()) {
            attemptStore.markFailed(attempt.getOrderNo(), "支付单不存在");
            return;
        }
        PaymentStatusView payment = paymentOptional.get();

        // KI-04：支付服务返回的数据必须与订单一致，否则不能据此入账。
        // 与编排器共用同一套校验，避免两条路径行为不一致。
        String mismatch = paymentMismatch(order, payment);
        if (mismatch != null) {
            handleMismatch(attempt, payment, mismatch);
            return;
        }

        switch (payment.getStatus()) {
            case STATUS_SUCCEEDED:
                if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                    completePaid(attempt, payment.getPaymentNo());
                } else if (order.getStatus() == OrderStatus.CANCELLED) {
                    refundAfterCancellation(attempt, payment.getPaymentNo());
                } else {
                    attemptStore.markSucceeded(order.getOrderNo(), payment.getPaymentNo());
                }
                break;
            case STATUS_CREATED:
                if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                    retryPayment(attempt, order);
                } else if (order.getStatus() == OrderStatus.CANCELLED) {
                    closePayment(attempt, payment.getPaymentNo());
                } else {
                    fail(attempt, "订单已支付但支付单未完成");
                }
                break;
            case STATUS_FAILED:
                attemptStore.markFailed(order.getOrderNo(), "支付单已关闭");
                break;
            case STATUS_REFUNDED:
                attemptStore.markRefunded(order.getOrderNo());
                break;
            default:
                fail(attempt, "未知支付状态：" + payment.getStatus());
        }
    }

    /**
     * 校验支付单与订单是否一致（KI-04）。
     *
     * @return 不一致时返回原因描述，一致返回 null
     */
    private String paymentMismatch(CustomerOrder order, PaymentStatusView payment) {
        if (!order.getOrderNo().equals(payment.getOrderNo())) {
            return "支付单订单号不一致：" + payment.getOrderNo();
        }
        if (payment.getUserId() == null || !payment.getUserId().equals(order.getUserId())) {
            return "支付单用户不一致：" + payment.getUserId();
        }
        if (payment.getAmount() == null
                || payment.getAmount().compareTo(order.getTotalAmount()) != 0) {
            return "支付单金额不一致：" + payment.getAmount();
        }
        return null;
    }

    /**
     * 处理不一致的支付单：已扣款的一律退款，未扣款的标记失败。
     */
    private void handleMismatch(OrderPaymentAttempt attempt, PaymentStatusView payment,
                                String reason) {
        log.error("Payment mismatch detected during reconciliation: orderNo={}, paymentNo={}, reason={}",
                attempt.getOrderNo(), payment.getPaymentNo(), reason);
        if (STATUS_SUCCEEDED.equals(payment.getStatus())) {
            refundAfterCancellation(attempt, payment.getPaymentNo(), "支付单与订单不一致，对账自动退款");
        } else {
            attemptStore.markFailed(attempt.getOrderNo(), reason);
        }
    }

    private void reconcileRefundPending(OrderPaymentAttempt attempt) {
        Optional<PaymentStatusView> paymentOptional;
        try {
            paymentOptional = paymentClient.findByOrder(attempt.getUserId(), attempt.getOrderNo());
        } catch (PaymentUnavailableException exception) {
            fail(attempt, exception.getMessage());
            return;
        }
        if (!paymentOptional.isPresent()) {
            attemptStore.markRefunded(attempt.getOrderNo());
            return;
        }
        PaymentStatusView payment = paymentOptional.get();
        switch (payment.getStatus()) {
            case STATUS_REFUNDED:
                attemptStore.markRefunded(attempt.getOrderNo());
                break;
            case STATUS_SUCCEEDED:
                refundAfterCancellation(attempt, payment.getPaymentNo());
                break;
            case STATUS_CREATED:
                closePayment(attempt, payment.getPaymentNo());
                break;
            case STATUS_FAILED:
                attemptStore.markRefunded(attempt.getOrderNo());
                break;
            default:
                fail(attempt, "未知支付状态：" + payment.getStatus());
        }
    }

    private void retryPayment(OrderPaymentAttempt attempt, CustomerOrder order) {
        PaymentView payment;
        try {
            payment = paymentClient.pay(PaymentCommand.builder()
                    .orderNo(order.getOrderNo())
                    .userId(order.getUserId())
                    .amount(order.getTotalAmount())
                    .currency(CURRENCY)
                    .build());
        } catch (PaymentClientException exception) {
            fail(attempt, exception.getMessage());
            return;
        }
        if (!STATUS_SUCCEEDED.equals(payment.getStatus())
                || payment.getAmount() == null
                || payment.getAmount().compareTo(order.getTotalAmount()) != 0) {
            fail(attempt, "重试支付结果异常：" + payment.getStatus());
            return;
        }
        completePaid(attempt, payment.getPaymentNo());
    }

    private void completePaid(OrderPaymentAttempt attempt, String paymentNo) {
        OrderPaymentAttemptStore.Completion completion =
                attemptStore.completePaid(attempt.getOrderNo(), paymentNo);
        if (completion == OrderPaymentAttemptStore.Completion.ORDER_CANCELLED) {
            log.info("Order cancelled during reconciliation, refunding: orderNo={}",
                    attempt.getOrderNo());
            refundAfterCancellation(attempt, paymentNo);
        }
    }

    private void refundAfterCancellation(OrderPaymentAttempt attempt, String paymentNo) {
        refundAfterCancellation(attempt, paymentNo, "订单已取消，对账自动退款");
    }

    private void refundAfterCancellation(OrderPaymentAttempt attempt, String paymentNo,
                                         String reason) {
        attemptStore.markRefundPending(attempt.getOrderNo(), paymentNo);
        try {
            paymentClient.refund(attempt.getUserId(), paymentNo, reason);
            attemptStore.markRefunded(attempt.getOrderNo());
        } catch (PaymentClientException exception) {
            fail(attempt, "退款失败：" + exception.getMessage());
        }
    }

    private void closePayment(OrderPaymentAttempt attempt, String paymentNo) {
        try {
            paymentClient.close(attempt.getUserId(), paymentNo, "订单已取消，对账关闭支付");
            attemptStore.markFailed(attempt.getOrderNo(), "订单已取消，支付已关闭");
        } catch (PaymentClientException exception) {
            if (exception.getStatusCode() != null && exception.getStatusCode() == 409) {
                attemptStore.markFailed(attempt.getOrderNo(), "支付单状态不可关闭");
            } else {
                fail(attempt, "关闭支付失败：" + exception.getMessage());
            }
        }
    }

    private void fail(OrderPaymentAttempt attempt, String error) {
        attemptStore.touch(attempt.getOrderNo(), error);
        if (attempt.getAttemptCount() + 1 >= alertThreshold) {
            log.error("Payment reconciliation needs attention: orderNo={}, status={}, attempts={}, error={}",
                    attempt.getOrderNo(), attempt.getStatus(), attempt.getAttemptCount() + 1, error);
        }
    }
}
