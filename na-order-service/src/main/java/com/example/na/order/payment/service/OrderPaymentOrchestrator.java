package com.example.na.order.payment.service;

import com.example.na.order.ordering.domain.CustomerOrder;
import com.example.na.order.ordering.domain.OrderStatus;
import com.example.na.order.ordering.repository.OrderRepository;
import com.example.na.order.payment.web.PaymentResponse;
import com.example.na.order.paymentattempt.domain.OrderPaymentAttempt;
import com.example.na.order.paymentattempt.domain.OrderPaymentAttemptStatus;
import com.example.na.order.paymentattempt.repository.OrderPaymentAttemptRepository;
import com.example.na.order.paymentattempt.service.OrderPaymentAttemptStore;
import com.example.na.order.paymentclient.PaymentClient;
import com.example.na.order.paymentclient.PaymentClientException;
import com.example.na.order.paymentclient.PaymentConflictException;
import com.example.na.order.paymentclient.PaymentNotFoundException;
import com.example.na.order.paymentclient.PaymentUnavailableException;
import com.example.na.order.shared.BusinessException;
import com.example.na.payment.contract.PaymentCommand;
import com.example.na.payment.contract.PaymentStatusView;
import com.example.na.payment.contract.PaymentView;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderPaymentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentOrchestrator.class);

    private static final String CURRENCY = "CNY";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_REFUNDED = "REFUNDED";

    private final OrderRepository orderRepository;
    private final OrderPaymentAttemptRepository attemptRepository;
    private final OrderPaymentAttemptStore attemptStore;
    private final PaymentClient paymentClient;

    public PaymentResult pay(Long userId, String orderNo) {
        CustomerOrder order = orderRepository.findByOrderNoAndUserId(orderNo, userId)
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw BusinessException.conflict("订单已取消，无法支付");
        }
        if (order.getStatus() == OrderStatus.PAID) {
            return alreadyPaid(userId, orderNo);
        }

        OrderPaymentAttemptStore.StartResult start =
                attemptStore.start(orderNo, userId, order.getTotalAmount());
        OrderPaymentAttempt attempt = start.getAttempt();
        if (attempt.getStatus() == OrderPaymentAttemptStatus.REFUND_PENDING
                || attempt.getStatus() == OrderPaymentAttemptStatus.REFUNDED) {
            throw BusinessException.conflict("支付单已进入退款流程");
        }
        if (attempt.getStatus() == OrderPaymentAttemptStatus.FAILED) {
            throw BusinessException.conflict("支付尝试已失败，请重新下单");
        }

        PaymentView payment;
        try {
            payment = paymentClient.pay(PaymentCommand.builder()
                    .orderNo(orderNo)
                    .userId(userId)
                    .amount(order.getTotalAmount())
                    .currency(CURRENCY)
                    .build());
        } catch (PaymentUnavailableException exception) {
            attemptStore.touch(orderNo, exception.getMessage());
            throw BusinessException.serviceUnavailable("支付服务暂时不可用，请稍后重试");
        } catch (PaymentNotFoundException exception) {
            attemptStore.markFailed(orderNo, "支付单不存在");
            throw BusinessException.conflict("支付失败：支付单不存在");
        } catch (PaymentConflictException exception) {
            compensateConflict(order, userId);
            throw BusinessException.conflict("支付失败：支付请求冲突");
        } catch (PaymentClientException exception) {
            attemptStore.markFailed(orderNo, exception.getMessage());
            throw BusinessException.conflict("支付失败：" + exception.getMessage());
        }

        validatePayment(order, userId, payment);
        return completePayment(order, payment, start.isCreated());
    }

    public PaymentResponse getPayment(Long userId, String paymentNo) {
        try {
            return toResponse(paymentClient.getPayment(userId, paymentNo));
        } catch (PaymentNotFoundException exception) {
            throw BusinessException.notFound("支付单不存在");
        } catch (PaymentUnavailableException exception) {
            throw BusinessException.serviceUnavailable("支付服务暂时不可用，请稍后重试");
        }
    }

    private PaymentResult alreadyPaid(Long userId, String orderNo) {
        Optional<OrderPaymentAttempt> attempt = attemptRepository.findByOrderNo(orderNo);
        if (attempt.isPresent() && attempt.get().getPaymentNo() != null) {
            try {
                return new PaymentResult(toResponse(
                        paymentClient.getPayment(userId, attempt.get().getPaymentNo())), false);
            } catch (PaymentNotFoundException exception) {
                log.warn("Paid order has no matching payment: orderNo={}, paymentNo={}",
                        orderNo, attempt.get().getPaymentNo());
            } catch (PaymentUnavailableException exception) {
                throw BusinessException.serviceUnavailable("支付服务暂时不可用，请稍后重试");
            }
        }
        try {
            Optional<PaymentStatusView> status = paymentClient.findByOrder(userId, orderNo);
            if (status.isPresent()) {
                return new PaymentResult(toResponse(
                        paymentClient.getPayment(userId, status.get().getPaymentNo())), false);
            }
        } catch (PaymentNotFoundException exception) {
            throw BusinessException.conflict("订单已支付，但未找到支付单");
        } catch (PaymentUnavailableException exception) {
            throw BusinessException.serviceUnavailable("支付服务暂时不可用，请稍后重试");
        }
        throw BusinessException.conflict("订单已支付，但未找到支付单");
    }

    private void validatePayment(CustomerOrder order, Long userId, PaymentView payment) {
        if (payment == null || payment.getPaymentNo() == null) {
            attemptStore.touch(order.getOrderNo(), "支付服务返回空响应");
            throw BusinessException.serviceUnavailable("支付服务暂时不可用，请稍后重试");
        }
        boolean mismatch = !order.getOrderNo().equals(payment.getOrderNo())
                || !userId.equals(payment.getUserId())
                || payment.getAmount() == null
                || payment.getAmount().compareTo(order.getTotalAmount()) != 0;
        if (mismatch) {
            compensateInvalidPayment(order, payment);
        }
        if (STATUS_REFUNDED.equals(payment.getStatus())) {
            attemptStore.markRefunded(order.getOrderNo());
            throw BusinessException.conflict("支付单已退款，无法完成支付");
        }
        if (!STATUS_SUCCEEDED.equals(payment.getStatus())) {
            attemptStore.markFailed(order.getOrderNo(), "支付未成功：" + payment.getStatus());
            throw BusinessException.conflict("支付未成功，请稍后重试");
        }
    }

    private void compensateConflict(CustomerOrder order, Long userId) {
        Optional<PaymentStatusView> existing;
        try {
            existing = paymentClient.findByOrder(userId, order.getOrderNo());
        } catch (PaymentClientException exception) {
            attemptStore.markFailed(order.getOrderNo(), exception.getMessage());
            return;
        }
        if (!existing.isPresent() || !STATUS_SUCCEEDED.equals(existing.get().getStatus())) {
            attemptStore.markFailed(order.getOrderNo(), "支付请求与已有支付单不一致");
            return;
        }
        String paymentNo = existing.get().getPaymentNo();
        attemptStore.markRefundPending(order.getOrderNo(), paymentNo);
        try {
            paymentClient.refund(userId, paymentNo, "支付金额或归属校验失败，自动退款");
            attemptStore.markRefunded(order.getOrderNo());
        } catch (PaymentClientException exception) {
            attemptStore.touch(order.getOrderNo(), "退款失败：" + exception.getMessage());
            throw BusinessException.serviceUnavailable("支付校验失败，退款处理中，请稍后重试");
        }
    }

    private void compensateInvalidPayment(CustomerOrder order, PaymentView payment) {
        String orderNo = order.getOrderNo();
        String reason = "订单支付校验失败，自动退款";
        if (STATUS_SUCCEEDED.equals(payment.getStatus())) {
            attemptStore.markRefundPending(orderNo, payment.getPaymentNo());
            try {
                paymentClient.refund(order.getUserId(), payment.getPaymentNo(), reason);
                attemptStore.markRefunded(orderNo);
            } catch (PaymentClientException exception) {
                attemptStore.touch(orderNo, "退款失败：" + exception.getMessage());
                throw BusinessException.serviceUnavailable("支付校验失败，退款处理中，请稍后重试");
            }
            throw BusinessException.conflict("支付金额或归属校验失败，已退款");
        }
        attemptStore.markFailed(orderNo, reason);
        throw BusinessException.conflict("支付金额或归属校验失败");
    }

    private PaymentResult completePayment(CustomerOrder order, PaymentView payment, boolean created) {
        OrderPaymentAttemptStore.Completion completion;
        try {
            completion = attemptStore.completePaid(order.getOrderNo(), payment.getPaymentNo());
        } catch (RuntimeException exception) {
            log.warn("Failed to complete order payment: orderNo={}, paymentNo={}",
                    order.getOrderNo(), payment.getPaymentNo(), exception);
            attemptStore.touch(order.getOrderNo(), "订单支付状态更新失败");
            throw BusinessException.serviceUnavailable("订单支付状态处理中，请稍后重试");
        }

        switch (completion) {
            case PAID:
                return new PaymentResult(toResponse(payment), created);
            case ALREADY_PAID:
                return new PaymentResult(toResponse(payment), false);
            case ORDER_CANCELLED:
                refundCancelledOrder(order, payment);
                throw BusinessException.conflict("订单已取消，支付已退款");
            default:
                attemptStore.touch(order.getOrderNo(), "订单状态未确认");
                throw BusinessException.serviceUnavailable("订单支付状态处理中，请稍后重试");
        }
    }

    private void refundCancelledOrder(CustomerOrder order, PaymentView payment) {
        String orderNo = order.getOrderNo();
        attemptStore.markRefundPending(orderNo, payment.getPaymentNo());
        try {
            paymentClient.refund(order.getUserId(), payment.getPaymentNo(), "订单已取消，自动退款");
            attemptStore.markRefunded(orderNo);
        } catch (PaymentClientException exception) {
            attemptStore.touch(orderNo, "退款失败：" + exception.getMessage());
            throw BusinessException.serviceUnavailable("订单已取消，退款处理中，请稍后重试");
        }
    }

    private PaymentResponse toResponse(PaymentView payment) {
        return new PaymentResponse(payment.getPaymentNo(), payment.getOrderNo(), payment.getAmount(),
                payment.getChannel(), payment.getStatus(), payment.getSucceededAt());
    }

    @Getter
    public static class PaymentResult {

        private final PaymentResponse payment;

        private final boolean created;

        PaymentResult(PaymentResponse payment, boolean created) {
            this.payment = payment;
            this.created = created;
        }
    }
}
