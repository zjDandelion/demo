package com.example.na.payment.service;

import com.example.na.payment.channel.PaymentChannel;
import com.example.na.payment.contract.PaymentCommand;
import com.example.na.payment.contract.PaymentStatusView;
import com.example.na.payment.contract.PaymentView;
import com.example.na.payment.domain.Payment;
import com.example.na.payment.domain.PaymentStatus;
import com.example.na.payment.repository.PaymentRepository;
import com.example.na.payment.shared.PaymentApiException;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final String CHANNEL_MOCK = "MOCK";

    private final PaymentRepository paymentRepository;
    private final PaymentNoGenerator paymentNoGenerator;
    private final PaymentChannel paymentChannel;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentNoGenerator paymentNoGenerator,
                          PaymentChannel paymentChannel,
                          PlatformTransactionManager transactionManager) {
        this.paymentRepository = paymentRepository;
        this.paymentNoGenerator = paymentNoGenerator;
        this.paymentChannel = paymentChannel;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PaymentResult pay(PaymentCommand command) {
        PaymentCommand normalized = normalizeAndValidate(command);
        Prepared prepared;
        try {
            prepared = transactionTemplate.execute(status -> prepare(normalized));
        } catch (DataIntegrityViolationException exception) {
            prepared = transactionTemplate.execute(status -> resumeAfterInsertConflict(normalized));
        }
        if (prepared == null) {
            throw PaymentApiException.internal("支付单创建失败");
        }
        final Prepared attempt = prepared;
        if (!attempt.needsChannel) {
            return new PaymentResult(toView(attempt.payment), attempt.created);
        }

        try {
            paymentChannel.charge(attempt.payment);
        } catch (RuntimeException exception) {
            log.warn("Payment channel charge failed: paymentNo={}, orderNo={}",
                    attempt.payment.getPaymentNo(), attempt.payment.getOrderNo(), exception);
            throw PaymentApiException.inProgress("支付渠道调用失败，结果未知");
        }

        return transactionTemplate.execute(status -> completePayment(attempt));
    }

    @Transactional(readOnly = true)
    public PaymentView getPayment(Long userId, String paymentNo) {
        Payment payment = paymentRepository.findByPaymentNoAndUserId(paymentNo, userId)
                .orElseThrow(() -> PaymentApiException.notFound("支付单不存在"));
        return toView(payment);
    }

    @Transactional(readOnly = true)
    public PaymentStatusView getPaymentByOrder(Long userId, String orderNo) {
        Payment payment = paymentRepository.findByOrderNoAndUserId(orderNo, userId)
                .orElseThrow(() -> PaymentApiException.notFound("支付单不存在"));
        return toStatusView(payment);
    }

    public PaymentView refund(String paymentNo, String reason) {
        String normalizedReason = normalizeReason(reason);
        Payment payment = paymentRepository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> PaymentApiException.notFound("支付单不存在"));
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return toView(payment);
        }
        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw PaymentApiException.refundNotAllowed("当前支付状态不可退款");
        }

        try {
            paymentChannel.refund(payment, normalizedReason);
        } catch (RuntimeException exception) {
            log.warn("Payment channel refund failed: paymentNo={}", paymentNo, exception);
            throw PaymentApiException.inProgress("退款渠道调用失败，结果未知");
        }

        return transactionTemplate.execute(status -> completeRefund(paymentNo, normalizedReason));
    }

    public PaymentView close(String paymentNo, String reason) {
        Payment payment = paymentRepository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> PaymentApiException.notFound("支付单不存在"));
        if (payment.getStatus() == PaymentStatus.FAILED) {
            return toView(payment);
        }
        if (payment.getStatus() != PaymentStatus.CREATED) {
            throw PaymentApiException.conflict("当前支付状态不可关闭");
        }

        try {
            paymentChannel.close(payment, reason);
        } catch (RuntimeException exception) {
            log.warn("Payment channel close failed: paymentNo={}", paymentNo, exception);
            throw PaymentApiException.inProgress("关闭渠道调用失败，结果未知");
        }

        return transactionTemplate.execute(status -> completeClose(paymentNo));
    }

    private PaymentCommand normalizeAndValidate(PaymentCommand command) {
        if (command == null) {
            throw PaymentApiException.invalid("支付请求不能为空");
        }
        String orderNo = trimToNull(command.getOrderNo());
        if (orderNo == null || orderNo.length() > 32) {
            throw PaymentApiException.invalid("订单号不能为空且长度不能超过 32");
        }
        if (command.getUserId() == null || command.getUserId() <= 0) {
            throw PaymentApiException.invalid("用户 ID 不合法");
        }
        BigDecimal amount = command.getAmount();
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw PaymentApiException.invalid("支付金额不合法");
        }
        String currency = trimToNull(command.getCurrency());
        if (currency == null || currency.length() != 3) {
            throw PaymentApiException.invalid("币种不合法");
        }
        return PaymentCommand.builder()
                .orderNo(orderNo)
                .userId(command.getUserId())
                .amount(amount)
                .currency(currency.toUpperCase(Locale.ROOT))
                .build();
    }

    private Prepared prepare(PaymentCommand command) {
        Payment existing = paymentRepository.findByOrderNo(command.getOrderNo()).orElse(null);
        if (existing != null) {
            return prepareExisting(existing, command);
        }
        Payment payment = new Payment(paymentNoGenerator.nextPaymentNo(), command.getOrderNo(),
                command.getUserId(), command.getAmount(), command.getCurrency(), CHANNEL_MOCK);
        paymentRepository.saveAndFlush(payment);
        log.info("Payment created: paymentNo={}, orderNo={}, amount={} {}",
                payment.getPaymentNo(), payment.getOrderNo(), payment.getAmount(), payment.getCurrency());
        return new Prepared(payment, true, true);
    }

    private Prepared resumeAfterInsertConflict(PaymentCommand command) {
        Payment existing = paymentRepository.findByOrderNo(command.getOrderNo())
                .orElseThrow(() -> PaymentApiException.conflict("支付单正在创建，请稍后重试"));
        return prepareExisting(existing, command);
    }

    private Prepared prepareExisting(Payment existing, PaymentCommand command) {
        ensureSamePayment(existing, command);
        switch (existing.getStatus()) {
            case SUCCEEDED:
                return new Prepared(existing, false, false);
            case CREATED:
                log.info("Resuming created payment: paymentNo={}, orderNo={}",
                        existing.getPaymentNo(), existing.getOrderNo());
                return new Prepared(existing, false, true);
            case FAILED:
                throw PaymentApiException.conflict("支付单已关闭");
            case REFUNDED:
                throw PaymentApiException.conflict("支付单已退款");
            default:
                throw PaymentApiException.internal("未知支付状态");
        }
    }

    private void ensureSamePayment(Payment payment, PaymentCommand command) {
        if (!payment.getUserId().equals(command.getUserId())) {
            throw PaymentApiException.conflict("支付请求与已有支付单的用户不一致");
        }
        if (payment.getAmount().compareTo(command.getAmount()) != 0) {
            throw PaymentApiException.conflict("支付请求与已有支付单的金额不一致");
        }
        if (!payment.getCurrency().equalsIgnoreCase(command.getCurrency())) {
            throw PaymentApiException.conflict("支付请求与已有支付单的币种不一致");
        }
    }

    private PaymentResult completePayment(Prepared prepared) {
        String paymentNo = prepared.payment.getPaymentNo();
        paymentRepository.markSucceeded(paymentNo, PaymentStatus.SUCCEEDED, PaymentStatus.CREATED,
                LocalDateTime.now());
        Payment current = paymentRepository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> PaymentApiException.internal("支付单不存在"));
        if (current.getStatus() == PaymentStatus.SUCCEEDED) {
            log.info("Payment succeeded: paymentNo={}, orderNo={}", paymentNo, current.getOrderNo());
            return new PaymentResult(toView(current), prepared.created);
        }
        if (current.getStatus() == PaymentStatus.FAILED) {
            throw PaymentApiException.conflict("支付单已关闭");
        }
        if (current.getStatus() == PaymentStatus.REFUNDED) {
            throw PaymentApiException.conflict("支付单已退款");
        }
        throw PaymentApiException.inProgress("支付单状态处理中，请稍后重试");
    }

    private PaymentView completeRefund(String paymentNo, String reason) {
        paymentRepository.markRefunded(paymentNo, PaymentStatus.REFUNDED, PaymentStatus.SUCCEEDED,
                LocalDateTime.now(), reason);
        Payment current = paymentRepository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> PaymentApiException.internal("支付单不存在"));
        if (current.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Payment refunded: paymentNo={}", paymentNo);
            return toView(current);
        }
        if (current.getStatus() == PaymentStatus.SUCCEEDED) {
            throw PaymentApiException.inProgress("退款状态未确认，请稍后重试");
        }
        throw PaymentApiException.conflict("当前支付状态不可退款");
    }

    private PaymentView completeClose(String paymentNo) {
        paymentRepository.markFailed(paymentNo, PaymentStatus.FAILED, PaymentStatus.CREATED);
        Payment current = paymentRepository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> PaymentApiException.internal("支付单不存在"));
        if (current.getStatus() == PaymentStatus.FAILED) {
            log.info("Payment closed: paymentNo={}", paymentNo);
            return toView(current);
        }
        if (current.getStatus() == PaymentStatus.CREATED) {
            throw PaymentApiException.inProgress("关闭状态未确认，请稍后重试");
        }
        throw PaymentApiException.conflict("当前支付状态不可关闭");
    }

    private String normalizeReason(String reason) {
        String trimmed = trimToNull(reason);
        if (trimmed != null && trimmed.length() > 200) {
            throw PaymentApiException.invalid("原因长度不能超过 200");
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private PaymentView toView(Payment payment) {
        return PaymentView.builder()
                .paymentNo(payment.getPaymentNo())
                .orderNo(payment.getOrderNo())
                .userId(payment.getUserId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus().name())
                .channel(payment.getChannel())
                .createdAt(payment.getCreatedAt())
                .succeededAt(payment.getSucceededAt())
                .refundedAt(payment.getRefundedAt())
                .refundReason(payment.getRefundReason())
                .build();
    }

    private PaymentStatusView toStatusView(Payment payment) {
        return PaymentStatusView.builder()
                .paymentNo(payment.getPaymentNo())
                .orderNo(payment.getOrderNo())
                .userId(payment.getUserId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus().name())
                .build();
    }

    @Getter
    public static class PaymentResult {

        private final PaymentView payment;

        private final boolean created;

        PaymentResult(PaymentView payment, boolean created) {
            this.payment = payment;
            this.created = created;
        }
    }

    private static final class Prepared {

        private final Payment payment;

        private final boolean created;

        private final boolean needsChannel;

        private Prepared(Payment payment, boolean created, boolean needsChannel) {
            this.payment = payment;
            this.created = created;
            this.needsChannel = needsChannel;
        }
    }
}
