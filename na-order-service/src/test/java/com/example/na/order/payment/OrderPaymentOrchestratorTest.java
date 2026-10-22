package com.example.na.order.payment;

import com.example.na.order.catalog.domain.Product;
import com.example.na.order.catalog.repository.ProductRepository;
import com.example.na.order.ordering.domain.CustomerOrder;
import com.example.na.order.ordering.domain.OrderStatus;
import com.example.na.order.ordering.repository.OrderRepository;
import com.example.na.order.ordering.service.OrderService;
import com.example.na.order.ordering.web.CreateOrderRequest;
import com.example.na.order.ordering.web.OrderResponse;
import com.example.na.order.payment.service.OrderPaymentOrchestrator;
import com.example.na.order.paymentattempt.domain.OrderPaymentAttempt;
import com.example.na.order.paymentattempt.domain.OrderPaymentAttemptStatus;
import com.example.na.order.paymentattempt.repository.OrderPaymentAttemptRepository;
import com.example.na.order.paymentclient.PaymentClient;
import com.example.na.order.paymentclient.PaymentNotFoundException;
import com.example.na.order.paymentclient.PaymentUnavailableException;
import com.example.na.order.shared.BusinessException;
import com.example.na.order.user.domain.User;
import com.example.na.order.user.repository.UserRepository;
import com.example.na.payment.contract.PaymentCommand;
import com.example.na.payment.contract.PaymentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class OrderPaymentOrchestratorTest {

    @Autowired
    private OrderPaymentOrchestrator orchestrator;

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
    void t5_paymentSucceedsAndMarksOrderPaid() {
        User user = createUser("payer");
        OrderResponse order = createOrder(user, "199.00", 10, 1);
        PaymentView payment = paymentView(order.getOrderNo(), user.getId(), order.getTotalAmount(),
                "SUCCEEDED");
        when(paymentClient.pay(any(PaymentCommand.class))).thenReturn(payment);

        OrderPaymentOrchestrator.PaymentResult result =
                orchestrator.pay(user.getId(), order.getOrderNo());

        assertTrue(result.isCreated());
        assertEquals("PAY-test", result.getPayment().getPaymentNo());
        assertEquals("SUCCEEDED", result.getPayment().getStatus());
        assertEquals(OrderStatus.PAID,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        OrderPaymentAttempt attempt = attemptRepository.findByOrderNo(order.getOrderNo()).get();
        assertEquals(OrderPaymentAttemptStatus.SUCCEEDED, attempt.getStatus());
        assertEquals("PAY-test", attempt.getPaymentNo());
        assertEquals(2, attempt.getAttemptCount());
    }

    @Test
    void repeatedPaymentReturnsExistingPaymentWithoutNewCharge() {
        User user = createUser("repeat");
        OrderResponse order = createOrder(user, "88.00", 5, 1);
        PaymentView payment = paymentView(order.getOrderNo(), user.getId(), order.getTotalAmount(),
                "SUCCEEDED");
        when(paymentClient.pay(any(PaymentCommand.class))).thenReturn(payment);
        when(paymentClient.getPayment(eq(user.getId()), eq("PAY-test"))).thenReturn(payment);

        orchestrator.pay(user.getId(), order.getOrderNo());
        OrderPaymentOrchestrator.PaymentResult second =
                orchestrator.pay(user.getId(), order.getOrderNo());

        assertFalse(second.isCreated());
        assertEquals("PAY-test", second.getPayment().getPaymentNo());
        assertEquals(OrderPaymentAttemptStatus.SUCCEEDED,
                attemptRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
    }

    @Test
    void paymentUnavailableKeepsOrderPendingAndAttemptStarted() {
        User user = createUser("offline");
        OrderResponse order = createOrder(user, "120.00", 5, 1);
        when(paymentClient.pay(any(PaymentCommand.class)))
                .thenThrow(new PaymentUnavailableException("支付服务暂时不可用"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orchestrator.pay(user.getId(), order.getOrderNo()));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        OrderPaymentAttempt attempt = attemptRepository.findByOrderNo(order.getOrderNo()).get();
        assertEquals(OrderPaymentAttemptStatus.STARTED, attempt.getStatus());
        assertEquals(2, attempt.getAttemptCount());
        assertTrue(attempt.getLastError().contains("支付服务暂时不可用"));
    }

    @Test
    void cancelRaceRefundsAndLeavesOrderCancelled() {
        User user = createUser("race");
        OrderResponse order = createOrder(user, "59.00", 3, 2);
        when(paymentClient.pay(any(PaymentCommand.class))).thenAnswer(invocation -> {
            orderService.cancelOrder(user.getId(), order.getOrderNo());
            return paymentView(order.getOrderNo(), user.getId(), order.getTotalAmount(), "SUCCEEDED");
        });
        when(paymentClient.refund(eq("PAY-test"), anyString()))
                .thenReturn(paymentView(order.getOrderNo(), user.getId(), order.getTotalAmount(),
                        "REFUNDED"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orchestrator.pay(user.getId(), order.getOrderNo()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(OrderStatus.CANCELLED,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        assertEquals(OrderPaymentAttemptStatus.REFUNDED,
                attemptRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        verify(paymentClient).refund(eq("PAY-test"), anyString());
    }

    @Test
    void amountMismatchTriggersRefundAndDoesNotMarkPaid() {
        User user = createUser("mismatch");
        OrderResponse order = createOrder(user, "100.00", 5, 1);
        PaymentView wrongAmount = paymentView(order.getOrderNo(), user.getId(),
                new BigDecimal("99.00"), "SUCCEEDED");
        when(paymentClient.pay(any(PaymentCommand.class))).thenReturn(wrongAmount);
        when(paymentClient.refund(eq("PAY-test"), anyString()))
                .thenReturn(paymentView(order.getOrderNo(), user.getId(),
                        new BigDecimal("99.00"), "REFUNDED"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orchestrator.pay(user.getId(), order.getOrderNo()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        assertEquals(OrderPaymentAttemptStatus.REFUNDED,
                attemptRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        verify(paymentClient).refund(eq("PAY-test"), anyString());
    }

    @Test
    void amountConflictFromPaymentServiceRefundsExistingCharge() {
        User user = createUser("conflict");
        OrderResponse order = createOrder(user, "100.00", 5, 1);
        when(paymentClient.pay(any(PaymentCommand.class)))
                .thenThrow(new com.example.na.order.paymentclient.PaymentConflictException(
                        "支付请求冲突"));
        when(paymentClient.findByOrder(user.getId(), order.getOrderNo()))
                .thenReturn(java.util.Optional.of(
                        com.example.na.payment.contract.PaymentStatusView.builder()
                                .paymentNo("PAY-conflict")
                                .orderNo(order.getOrderNo())
                                .userId(user.getId())
                                .amount(new BigDecimal("99.00"))
                                .currency("CNY")
                                .status("SUCCEEDED")
                                .build()));
        when(paymentClient.refund(eq("PAY-conflict"), anyString()))
                .thenReturn(paymentView(order.getOrderNo(), user.getId(),
                        new BigDecimal("99.00"), "REFUNDED"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orchestrator.pay(user.getId(), order.getOrderNo()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        assertEquals(OrderPaymentAttemptStatus.REFUNDED,
                attemptRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        verify(paymentClient).refund(eq("PAY-conflict"), anyString());
    }

    @Test
    void t8_otherUsersPaymentIsNotFound() {
        when(paymentClient.getPayment(eq(99L), eq("PAY-test")))
                .thenThrow(new PaymentNotFoundException("支付单不存在"));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orchestrator.getPayment(99L, "PAY-test"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void failedAttemptIsRetriedByNextPayment() {
        User user = createUser("retry");
        OrderResponse order = createOrder(user, "66.00", 5, 1);
        PaymentView payment = paymentView(order.getOrderNo(), user.getId(), order.getTotalAmount(),
                "SUCCEEDED");
        when(paymentClient.pay(any(PaymentCommand.class)))
                .thenThrow(new PaymentNotFoundException("支付单不存在"))
                .thenReturn(payment);

        assertThrows(BusinessException.class, () -> orchestrator.pay(user.getId(), order.getOrderNo()));
        assertEquals(OrderPaymentAttemptStatus.FAILED,
                attemptRepository.findByOrderNo(order.getOrderNo()).get().getStatus());

        OrderPaymentOrchestrator.PaymentResult retry =
                orchestrator.pay(user.getId(), order.getOrderNo());

        assertEquals("PAY-test", retry.getPayment().getPaymentNo());
        assertEquals(OrderStatus.PAID,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
        assertEquals(OrderPaymentAttemptStatus.SUCCEEDED,
                attemptRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
    }

    private User createUser(String username) {
        return userRepository.save(new User(username, "not-used-in-test", username));
    }

    private OrderResponse createOrder(User user, String price, int stock, int quantity) {
        Product product = productRepository.save(new Product("商品-" + price, "测试商品",
                new BigDecimal(price), stock, true));
        CreateOrderRequest request = new CreateOrderRequest(Collections.singletonList(
                new CreateOrderRequest.OrderItemRequest(product.getId(), quantity)));
        return orderService.createOrder(user.getId(), request);
    }

    private PaymentView paymentView(String orderNo, Long userId, BigDecimal amount, String status) {
        return PaymentView.builder()
                .paymentNo("PAY-test")
                .orderNo(orderNo)
                .userId(userId)
                .amount(amount)
                .currency("CNY")
                .status(status)
                .channel("MOCK")
                .createdAt(LocalDateTime.now())
                .succeededAt("SUCCEEDED".equals(status) || "REFUNDED".equals(status)
                        ? LocalDateTime.now() : null)
                .build();
    }
}
