package com.example.na.order.payment;

import com.example.na.order.catalog.domain.Product;
import com.example.na.order.catalog.repository.ProductRepository;
import com.example.na.order.ordering.domain.OrderStatus;
import com.example.na.order.ordering.repository.OrderRepository;
import com.example.na.order.ordering.service.OrderService;
import com.example.na.order.ordering.web.CreateOrderRequest;
import com.example.na.order.ordering.web.OrderResponse;
import com.example.na.order.payment.reconciliation.OrderPaymentReconciliationJob;
import com.example.na.order.paymentattempt.domain.OrderPaymentAttempt;
import com.example.na.order.paymentattempt.domain.OrderPaymentAttemptStatus;
import com.example.na.order.paymentattempt.repository.OrderPaymentAttemptRepository;
import com.example.na.order.paymentclient.PaymentClient;
import com.example.na.order.paymentclient.PaymentUnavailableException;
import com.example.na.order.user.domain.User;
import com.example.na.order.user.repository.UserRepository;
import com.example.na.payment.contract.PaymentCommand;
import com.example.na.payment.contract.PaymentStatusView;
import com.example.na.payment.contract.PaymentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.reconciliation.enabled=true",
        "app.reconciliation.stale-seconds=0",
        "app.reconciliation.delay-ms=3600000",
        "app.reconciliation.initial-delay-ms=3600000"
})
class OrderPaymentReconciliationJobTest {

    @Autowired
    private OrderPaymentReconciliationJob job;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderPaymentAttemptRepository attemptRepository;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private PaymentClient paymentClient;

    @BeforeEach
    void setUp() {
        attemptRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void s5_paymentSucceededButOrderNotMarkedPaidIsRecovered() {
        User user = createUser("s5");
        OrderResponse order = createOrder(user);
        createAttempt(order, user, OrderPaymentAttemptStatus.STARTED);
        when(paymentClient.findByOrder(user.getId(), order.getOrderNo()))
                .thenReturn(Optional.of(statusView(order, user, "SUCCEEDED")));

        job.reconcile();

        assertEquals(OrderStatus.PAID,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        OrderPaymentAttempt attempt = attemptRepository.findByOrderNo(order.getOrderNo()).get();
        assertEquals(OrderPaymentAttemptStatus.SUCCEEDED, attempt.getStatus());
        assertEquals("PAY-reconcile", attempt.getPaymentNo());
    }

    @Test
    void s6_cancelledOrderWithSucceededPaymentIsRefunded() {
        User user = createUser("s6");
        OrderResponse order = createOrder(user);
        orderService.cancelOrder(user.getId(), order.getOrderNo());
        createAttempt(order, user, OrderPaymentAttemptStatus.STARTED);
        when(paymentClient.findByOrder(user.getId(), order.getOrderNo()))
                .thenReturn(Optional.of(statusView(order, user, "SUCCEEDED")));
        when(paymentClient.refund(any(), eq("PAY-reconcile"), anyString()))
                .thenReturn(paymentView(order, user, "REFUNDED"));

        job.reconcile();

        verify(paymentClient).refund(any(), eq("PAY-reconcile"), anyString());
        assertEquals(OrderPaymentAttemptStatus.REFUNDED,
                attemptRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        assertEquals(OrderStatus.CANCELLED,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
    }

    @Test
    void s3_paymentUnavailableRetainsStartedAttemptForNextRound() {
        User user = createUser("s3");
        OrderResponse order = createOrder(user);
        createAttempt(order, user, OrderPaymentAttemptStatus.STARTED);
        when(paymentClient.findByOrder(user.getId(), order.getOrderNo()))
                .thenThrow(new PaymentUnavailableException("支付服务暂时不可用"));

        job.reconcile();

        OrderPaymentAttempt attempt = attemptRepository.findByOrderNo(order.getOrderNo()).get();
        assertEquals(OrderPaymentAttemptStatus.STARTED, attempt.getStatus());
        assertEquals(2, attempt.getAttemptCount());
        assertEquals(OrderStatus.PENDING_PAYMENT,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
    }

    @Test
    void refundPendingWithRefundedPaymentIsClosed() {
        User user = createUser("refunded");
        OrderResponse order = createOrder(user);
        orderService.cancelOrder(user.getId(), order.getOrderNo());
        createAttempt(order, user, OrderPaymentAttemptStatus.REFUND_PENDING);
        when(paymentClient.findByOrder(user.getId(), order.getOrderNo()))
                .thenReturn(Optional.of(statusView(order, user, "REFUNDED")));

        job.reconcile();

        assertEquals(OrderPaymentAttemptStatus.REFUNDED,
                attemptRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
    }

    @Test
    void startedAttemptWithoutPaymentIsFailed() {
        User user = createUser("missing");
        OrderResponse order = createOrder(user);
        createAttempt(order, user, OrderPaymentAttemptStatus.STARTED);
        when(paymentClient.findByOrder(user.getId(), order.getOrderNo()))
                .thenReturn(Optional.empty());

        job.reconcile();

        assertEquals(OrderPaymentAttemptStatus.FAILED,
                attemptRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
    }

    @Test
    void createdPaymentForPendingOrderIsResumed() {
        User user = createUser("resume");
        OrderResponse order = createOrder(user);
        createAttempt(order, user, OrderPaymentAttemptStatus.STARTED);
        when(paymentClient.findByOrder(user.getId(), order.getOrderNo()))
                .thenReturn(Optional.of(statusView(order, user, "CREATED")));
        when(paymentClient.pay(any(PaymentCommand.class)))
                .thenReturn(paymentView(order, user, "SUCCEEDED"));

        job.reconcile();

        assertEquals(OrderStatus.PAID,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        assertEquals(OrderPaymentAttemptStatus.SUCCEEDED,
                attemptRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
    }

    private User createUser(String username) {
        return userRepository.save(new User(username, "not-used-in-test", username));
    }

    private OrderResponse createOrder(User user) {
        Product product = productRepository.save(new Product("商品-" + user.getUsername(), "测试商品",
                new BigDecimal("66.00"), 5, true));
        CreateOrderRequest request = new CreateOrderRequest(Collections.singletonList(
                new CreateOrderRequest.OrderItemRequest(product.getId(), 1)));
        return orderService.createOrder(user.getId(), request);
    }

    private void createAttempt(OrderResponse order, User user, OrderPaymentAttemptStatus status) {
        OrderPaymentAttempt attempt = new OrderPaymentAttempt(order.getOrderNo(), user.getId(),
                order.getTotalAmount(), status);
        attempt.setAttemptCount(1);
        attempt.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        attemptRepository.saveAndFlush(attempt);
    }

    // ---------------------------------------------------------------- KI-04
    // 对账遇到「支付服务返回的数据与订单不一致」时不能据此入账。

    @Test
    void ki04_succeededWithWrongAmountIsRefundedNotMarkedPaid() {
        User user = createUser("ki04-amount");
        OrderResponse order = createOrder(user);
        createAttempt(order, user, OrderPaymentAttemptStatus.STARTED);
        PaymentStatusView wrong = PaymentStatusView.builder()
                .paymentNo("PAY-reconcile")
                .orderNo(order.getOrderNo())
                .userId(user.getId())
                .amount(order.getTotalAmount().add(new BigDecimal("1.00")))
                .currency("CNY")
                .status("SUCCEEDED")
                .build();
        when(paymentClient.findByOrder(user.getId(), order.getOrderNo()))
                .thenReturn(Optional.of(wrong));
        when(paymentClient.refund(any(), eq("PAY-reconcile"), anyString()))
                .thenReturn(paymentView(order, user, "REFUNDED"));

        job.reconcile();

        // 不能入账，且已扣款的必须退款
        assertEquals(OrderStatus.PENDING_PAYMENT,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        assertEquals(OrderPaymentAttemptStatus.REFUNDED,
                attemptRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        verify(paymentClient).refund(any(), eq("PAY-reconcile"), anyString());
    }

    @Test
    void ki04_succeededWithWrongUserIsRefundedNotMarkedPaid() {
        User user = createUser("ki04-user");
        OrderResponse order = createOrder(user);
        createAttempt(order, user, OrderPaymentAttemptStatus.STARTED);
        PaymentStatusView wrong = PaymentStatusView.builder()
                .paymentNo("PAY-reconcile")
                .orderNo(order.getOrderNo())
                .userId(999L)
                .amount(order.getTotalAmount())
                .currency("CNY")
                .status("SUCCEEDED")
                .build();
        when(paymentClient.findByOrder(user.getId(), order.getOrderNo()))
                .thenReturn(Optional.of(wrong));
        when(paymentClient.refund(any(), eq("PAY-reconcile"), anyString()))
                .thenReturn(paymentView(order, user, "REFUNDED"));

        job.reconcile();

        assertEquals(OrderStatus.PENDING_PAYMENT,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        assertEquals(OrderPaymentAttemptStatus.REFUNDED,
                attemptRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        verify(paymentClient).refund(any(), eq("PAY-reconcile"), anyString());
    }

    @Test
    void ki04_createdWithWrongAmountIsMarkedFailedNotRetried() {
        User user = createUser("ki04-created");
        OrderResponse order = createOrder(user);
        createAttempt(order, user, OrderPaymentAttemptStatus.STARTED);
        PaymentStatusView wrong = PaymentStatusView.builder()
                .paymentNo("PAY-reconcile")
                .orderNo(order.getOrderNo())
                .userId(user.getId())
                .amount(order.getTotalAmount().add(new BigDecimal("5.00")))
                .currency("CNY")
                .status("CREATED")
                .build();
        when(paymentClient.findByOrder(user.getId(), order.getOrderNo()))
                .thenReturn(Optional.of(wrong));

        job.reconcile();

        // 未扣款，不应重试支付，直接标记失败等人工处理
        assertEquals(OrderPaymentAttemptStatus.FAILED,
                attemptRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        verify(paymentClient, never()).pay(any());
    }

    private PaymentStatusView statusView(OrderResponse order, User user, String status) {        return PaymentStatusView.builder()
                .paymentNo("PAY-reconcile")
                .orderNo(order.getOrderNo())
                .userId(user.getId())
                .amount(order.getTotalAmount())
                .currency("CNY")
                .status(status)
                .build();
    }

    private PaymentView paymentView(OrderResponse order, User user, String status) {
        return PaymentView.builder()
                .paymentNo("PAY-reconcile")
                .orderNo(order.getOrderNo())
                .userId(user.getId())
                .amount(order.getTotalAmount())
                .currency("CNY")
                .status(status)
                .channel("MOCK")
                .createdAt(LocalDateTime.now())
                .succeededAt(LocalDateTime.now())
                .build();
    }
}
