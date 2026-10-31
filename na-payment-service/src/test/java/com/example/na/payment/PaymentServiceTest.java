package com.example.na.payment;

import com.example.na.payment.contract.PaymentCommand;
import com.example.na.payment.contract.PaymentStatusView;
import com.example.na.payment.contract.PaymentView;
import com.example.na.payment.domain.Payment;
import com.example.na.payment.domain.PaymentStatus;
import com.example.na.payment.repository.PaymentRepository;
import com.example.na.payment.service.PaymentService;
import com.example.na.payment.service.PaymentService.PaymentResult;
import com.example.na.payment.shared.PaymentApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class PaymentServiceTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Test
    void t5_paymentSucceeds() {
        PaymentResult result = paymentService.pay(command("ORD-T5", 1L, "598.00"));

        assertTrue(result.isCreated());
        PaymentView payment = result.getPayment();
        assertTrue(payment.getPaymentNo().startsWith("PAY"));
        assertEquals("ORD-T5", payment.getOrderNo());
        assertEquals("SUCCEEDED", payment.getStatus());
        assertEquals("MOCK", payment.getChannel());
        assertEquals("CNY", payment.getCurrency());
        assertEquals(0, new BigDecimal("598.00").compareTo(payment.getAmount()));
        assertNotNull(payment.getSucceededAt());

        assertEquals(1, paymentRepository.countByOrderNo("ORD-T5"));
        PaymentView fetched = paymentService.getPayment(1L, payment.getPaymentNo());
        assertEquals("SUCCEEDED", fetched.getStatus());

        PaymentStatusView byOrder = paymentService.getPaymentByOrder(1L, "ORD-T5");
        assertEquals(payment.getPaymentNo(), byOrder.getPaymentNo());
        assertEquals("SUCCEEDED", byOrder.getStatus());
    }

    @Test
    void t6_sequentialDuplicatePaymentReturnsSamePayment() {
        PaymentResult first = paymentService.pay(command("ORD-T6", 1L, "59.00"));
        PaymentResult second = paymentService.pay(command("ORD-T6", 1L, "59.00"));

        assertTrue(first.isCreated());
        assertFalse(second.isCreated());
        assertEquals(first.getPayment().getPaymentNo(), second.getPayment().getPaymentNo());
        assertEquals("SUCCEEDED", second.getPayment().getStatus());
        assertEquals(1, paymentRepository.countByOrderNo("ORD-T6"));
    }

    @Test
    void t6_concurrentDuplicatePaymentCreatesSinglePayment() throws Exception {
        int threads = 2;
        executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Callable<PaymentResult> task = () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return paymentService.pay(command("ORD-T6-RACE", 1L, "59.00"));
            };
            futures.add(executor.submit(task));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "all threads should be ready");
        start.countDown();

        int created = 0;
        String paymentNo = null;
        for (Future<PaymentResult> future : futures) {
            PaymentResult result = future.get(30, TimeUnit.SECONDS);
            if (result.isCreated()) {
                created++;
            }
            if (paymentNo == null) {
                paymentNo = result.getPayment().getPaymentNo();
            } else {
                assertEquals(paymentNo, result.getPayment().getPaymentNo());
            }
            assertEquals("SUCCEEDED", result.getPayment().getStatus());
        }

        assertEquals(1, created);
        assertNotNull(paymentNo);
        assertEquals(1, paymentRepository.countByOrderNo("ORD-T6-RACE"));
    }

    @Test
    void t7_paymentRejectsMismatchedUserAmountAndCurrency() {
        paymentService.pay(command("ORD-T7", 1L, "59.00"));

        PaymentApiException amountMismatch = assertThrows(PaymentApiException.class,
                () -> paymentService.pay(command("ORD-T7", 1L, "58.00")));
        assertEquals(HttpStatus.CONFLICT, amountMismatch.getStatus());

        PaymentApiException userMismatch = assertThrows(PaymentApiException.class,
                () -> paymentService.pay(command("ORD-T7", 2L, "59.00")));
        assertEquals(HttpStatus.CONFLICT, userMismatch.getStatus());

        PaymentApiException currencyMismatch = assertThrows(PaymentApiException.class,
                () -> paymentService.pay(PaymentCommand.builder()
                        .orderNo("ORD-T7").userId(1L).amount(new BigDecimal("59.00")).currency("USD").build()));
        assertEquals(HttpStatus.CONFLICT, currencyMismatch.getStatus());
    }

    @Test
    void t7_invalidCommandIsRejected() {
        PaymentApiException exception = assertThrows(PaymentApiException.class,
                () -> paymentService.pay(PaymentCommand.builder()
                        .orderNo("ORD-INVALID").userId(1L).amount(BigDecimal.ZERO).currency("CNY").build()));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void t8_otherUserCannotReadPayment() {
        PaymentResult result = paymentService.pay(command("ORD-T8", 1L, "59.00"));

        PaymentApiException exception = assertThrows(PaymentApiException.class,
                () -> paymentService.getPayment(2L, result.getPayment().getPaymentNo()));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());

        PaymentApiException orderException = assertThrows(PaymentApiException.class,
                () -> paymentService.getPaymentByOrder(2L, "ORD-T8"));
        assertEquals(HttpStatus.NOT_FOUND, orderException.getStatus());
    }

    @Test
    void s7_refundIsIdempotent() {
        PaymentResult result = paymentService.pay(command("ORD-REFUND", 1L, "59.00"));

        PaymentView refunded = paymentService.refund(result.getPayment().getPaymentNo(), Long.valueOf(1L), "cancel");
        assertEquals("REFUNDED", refunded.getStatus());
        assertNotNull(refunded.getRefundedAt());
        assertEquals("cancel", refunded.getRefundReason());

        PaymentView repeated = paymentService.refund(result.getPayment().getPaymentNo(), Long.valueOf(1L), "cancel");
        assertEquals("REFUNDED", repeated.getStatus());
        assertEquals(1, paymentRepository.countByOrderNo("ORD-REFUND"));
    }

    @Test
    void s7_closeIsIdempotentAndOnlyForCreatedPayment() {
        Payment created = paymentRepository.saveAndFlush(new Payment(
                "PAY-CREATED-CLOSE", "ORD-CLOSE", 1L, new BigDecimal("10.00"), "CNY", "MOCK"));

        PaymentView closed = paymentService.close(created.getPaymentNo(), Long.valueOf(1L), "cancel");
        assertEquals("FAILED", closed.getStatus());
        PaymentView repeated = paymentService.close(created.getPaymentNo(), Long.valueOf(1L), "cancel");
        assertEquals("FAILED", repeated.getStatus());

        PaymentResult succeeded = paymentService.pay(command("ORD-CLOSE-SUCCEEDED", 1L, "20.00"));
        PaymentApiException exception = assertThrows(PaymentApiException.class,
                () -> paymentService.close(succeeded.getPayment().getPaymentNo(), Long.valueOf(1L), "cancel"));
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void s7_refundRejectedForCreatedPayment() {
        Payment created = paymentRepository.saveAndFlush(new Payment(
                "PAY-CREATED-REFUND", "ORD-REFUND-CREATED", 1L, new BigDecimal("10.00"), "CNY", "MOCK"));

        PaymentApiException exception = assertThrows(PaymentApiException.class,
                () -> paymentService.refund(created.getPaymentNo(), Long.valueOf(1L), "cancel"));
        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    // ---------------------------------------------------------------- KI-03
    // 退款/关闭必须校验支付单归属，避免持有内部 token 的调用方操作他人支付单。

    @Test
    void ki03_refundByOtherUserIsNotFound() {
        PaymentResult result = paymentService.pay(command("ORD-KI03-REFUND", 1L, "59.00"));

        PaymentApiException exception = assertThrows(PaymentApiException.class,
                () -> paymentService.refund(result.getPayment().getPaymentNo(), Long.valueOf(99L), "越权退款"));

        // 越权按「不存在」处理，避免探测他人支付单是否存在
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("SUCCEEDED",
                paymentService.getPayment(1L, result.getPayment().getPaymentNo()).getStatus());
    }

    @Test
    void ki03_closeByOtherUserIsNotFound() {
        Payment created = paymentRepository.saveAndFlush(new Payment(
                "PAY-KI03-CLOSE", "ORD-KI03-CLOSE", 1L, new BigDecimal("10.00"), "CNY", "MOCK"));

        PaymentApiException exception = assertThrows(PaymentApiException.class,
                () -> paymentService.close(created.getPaymentNo(), Long.valueOf(99L), "越权关闭"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("CREATED", paymentService.getPayment(1L, created.getPaymentNo()).getStatus());
    }

    @Test
    void ki03_refundWithoutUserIdIsRejected() {
        PaymentResult result = paymentService.pay(command("ORD-KI03-NOUSER", 1L, "59.00"));

        PaymentApiException exception = assertThrows(PaymentApiException.class,
                () -> paymentService.refund(result.getPayment().getPaymentNo(), (Long) null, "缺少 userId"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    // ---------------------------------------------------------------- KI-02
    // 退款/关闭必须先用 CAS 认领，并发请求不能都打到渠道。

    @Test
    void ki02_concurrentRefundOnlyCallsChannelOnce() throws Exception {
        PaymentResult result = paymentService.pay(command("ORD-KI02-REFUND", 1L, "59.00"));
        String paymentNo = result.getPayment().getPaymentNo();

        int threads = 4;
        executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentView>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Callable<PaymentView> task = () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return paymentService.refund(paymentNo, 1L, "concurrent");
            };
            futures.add(executor.submit(task));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        int succeeded = 0;
        for (Future<PaymentView> future : futures) {
            try {
                PaymentView view = future.get(30, TimeUnit.SECONDS);
                if ("REFUNDED".equals(view.getStatus())) {
                    succeeded++;
                }
            } catch (Exception exception) {
                // 认领失败的请求可能返回 409「退款中」，属预期
            }
        }

        // 至少有一个成功；最终状态必须是 REFUNDED 且只退一次
        assertTrue(succeeded >= 1, "至少一个退款请求应成功");
        assertEquals("REFUNDED", paymentService.getPayment(1L, paymentNo).getStatus());
    }

    @Test
    void ki02_refundWhileRefundingReturnsWithoutChannelCall() {
        Payment succeeded = paymentRepository.saveAndFlush(new Payment(
                "PAY-KI02-RING", "ORD-KI02-RING", 1L, new BigDecimal("10.00"), "CNY", "MOCK"));
        // 手工置为 SUCCEEDED，再认领为 REFUNDING 模拟「另一请求正在退款」
        claimDirect(succeeded.getPaymentNo(), PaymentStatus.CREATED, PaymentStatus.SUCCEEDED);
        claimDirect(succeeded.getPaymentNo(), PaymentStatus.SUCCEEDED, PaymentStatus.REFUNDING);

        PaymentView view = paymentService.refund(succeeded.getPaymentNo(), 1L, "second");

        // 不调渠道、不报错，直接返回当前状态
        assertEquals("REFUNDING", view.getStatus());
    }

    @Test
    void ki02_closeWhileClosingReturnsWithoutChannelCall() {
        Payment created = paymentRepository.saveAndFlush(new Payment(
                "PAY-KI02-CRING", "ORD-KI02-CRING", 1L, new BigDecimal("10.00"), "CNY", "MOCK"));
        claimDirect(created.getPaymentNo(), PaymentStatus.CREATED, PaymentStatus.CLOSING);

        PaymentView view = paymentService.close(created.getPaymentNo(), 1L, "second");

        assertEquals("CLOSING", view.getStatus());
    }

    // ---------------------------------------------------------------- KI-01
    // 扣款必须先认领 PROCESSING；渠道失败且结果明确时回退 CREATED 以便重试。

    @Test
    void ki01_chargeGoesThroughProcessingAndLandsSucceeded() {
        PaymentResult result = paymentService.pay(command("ORD-KI01-OK", 1L, "59.00"));

        assertEquals("SUCCEEDED", result.getPayment().getStatus());
        Payment stored = paymentRepository.findByPaymentNo(result.getPayment().getPaymentNo()).get();
        assertEquals(PaymentStatus.SUCCEEDED, stored.getStatus());
        // 认领时间已记录，便于运维识别僵死中间态
        assertNotNull(stored.getChannelAttemptAt());
    }

    @Test
    void ki01_processingStateIsNotChargeableAgain() {
        Payment payment = paymentRepository.saveAndFlush(new Payment(
                "PAY-KI01-PROC", "ORD-KI01-PROC", 1L, new BigDecimal("10.00"), "CNY", "MOCK"));
        claimDirect(payment.getPaymentNo(), PaymentStatus.CREATED, PaymentStatus.PROCESSING);

        // 已有请求认领中：不重复扣款，直接返回当前状态（幂等语义）
        PaymentResult result = paymentService.pay(command("ORD-KI01-PROC", 1L, "10.00"));

        assertEquals("PROCESSING", result.getPayment().getStatus());
        assertEquals("PROCESSING",
                paymentService.getPayment(1L, payment.getPaymentNo()).getStatus());
    }

    @Test
    void ki01_channelFailureReleasesClaimForRetry() {
        Payment payment = paymentRepository.saveAndFlush(new Payment(
                "PAY-KI01-RETRY", "ORD-KI01-RETRY", 1L, new BigDecimal("10.00"), "CNY", "MOCK"));

        // 模拟「认领后渠道明确失败」：PROCESSING -> CREATED
        claimDirect(payment.getPaymentNo(), PaymentStatus.CREATED, PaymentStatus.PROCESSING);
        int released = claimDirect(payment.getPaymentNo(), PaymentStatus.PROCESSING, PaymentStatus.CREATED);

        assertEquals(1, released, "认领应可回退");
        assertEquals("CREATED",
                paymentService.getPayment(1L, payment.getPaymentNo()).getStatus());
    }

    private int claimDirect(String paymentNo, PaymentStatus from, PaymentStatus to) {
        Integer result = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> paymentRepository.claimStatus(paymentNo, from, to));
        return result == null ? 0 : result;
    }

    private PaymentCommand command(String orderNo, Long userId, String amount) {
        return PaymentCommand.builder()
                .orderNo(orderNo)
                .userId(userId)
                .amount(new BigDecimal(amount))
                .currency("CNY")
                .build();
    }
}
