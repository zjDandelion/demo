package com.example.na.order.payment;

import com.example.na.order.catalog.domain.Product;
import com.example.na.order.catalog.repository.ProductRepository;
import com.example.na.order.ordering.repository.OrderRepository;
import com.example.na.order.ordering.service.OrderService;
import com.example.na.order.ordering.web.CreateOrderRequest;
import com.example.na.order.ordering.web.OrderResponse;
import com.example.na.order.paymentattempt.repository.OrderPaymentAttemptRepository;
import com.example.na.order.paymentclient.PaymentClient;
import com.example.na.order.paymentclient.PaymentUnavailableException;
import com.example.na.order.user.domain.User;
import com.example.na.order.user.repository.UserRepository;
import com.example.na.order.user.security.AuthenticatedUser;
import com.example.na.payment.contract.PaymentCommand;
import com.example.na.payment.contract.PaymentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentBffApiTest {

    @Autowired
    private MockMvc mockMvc;

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
    void firstPaymentReturns201AndRepeatReturns200() throws Exception {
        User user = createUser("bff");
        OrderResponse order = createOrder(user);
        PaymentView payment = paymentView(order, user);
        when(paymentClient.pay(any(PaymentCommand.class))).thenReturn(payment);
        when(paymentClient.getPayment(eq(user.getId()), eq("PAY-bff"))).thenReturn(payment);

        mockMvc.perform(post("/api/payments")
                        .with(user(principal(user)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + order.getOrderNo() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentNo").value("PAY-bff"))
                .andExpect(jsonPath("$.orderNo").value(order.getOrderNo()))
                .andExpect(jsonPath("$.amount").value(66.00))
                .andExpect(jsonPath("$.channel").value("MOCK"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.succeededAt").exists());

        mockMvc.perform(post("/api/payments")
                        .with(user(principal(user)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + order.getOrderNo() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentNo").value("PAY-bff"));
    }

    @Test
    void paymentUnavailableReturns503AndKeepsOrderPending() throws Exception {
        User user = createUser("bff-offline");
        OrderResponse order = createOrder(user);
        when(paymentClient.pay(any(PaymentCommand.class)))
                .thenThrow(new PaymentUnavailableException("down"));

        mockMvc.perform(post("/api/payments")
                        .with(user(principal(user)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + order.getOrderNo() + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("支付服务暂时不可用，请稍后重试"));

        org.junit.jupiter.api.Assertions.assertEquals(
                com.example.na.order.ordering.domain.OrderStatus.PENDING_PAYMENT,
                orderRepository.findByOrderNo(order.getOrderNo()).get().getStatus());
    }

    @Test
    void cancelledOrderReturns409() throws Exception {
        User user = createUser("bff-cancel");
        OrderResponse order = createOrder(user);
        orderService.cancelOrder(user.getId(), order.getOrderNo());

        mockMvc.perform(post("/api/payments")
                        .with(user(principal(user)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + order.getOrderNo() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("订单已取消，无法支付"));
    }

    @Test
    void unauthenticatedPaymentReturns401Json() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"ORD-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("未登录或会话已过期"));
    }

    private AuthenticatedUser principal(User user) {
        return new AuthenticatedUser(user.getId(), user.getUsername(), "not-used", user.getDisplayName());
    }

    private User createUser(String username) {
        return userRepository.save(new User(username, "not-used-in-test", username));
    }

    private OrderResponse createOrder(User user) {
        Product product = productRepository.save(new Product("BFF商品", "测试商品",
                new BigDecimal("66.00"), 5, true));
        return orderService.createOrder(user.getId(), new CreateOrderRequest(
                Collections.singletonList(
                        new CreateOrderRequest.OrderItemRequest(product.getId(), 1))));
    }

    private PaymentView paymentView(OrderResponse order, User user) {
        return PaymentView.builder()
                .paymentNo("PAY-bff")
                .orderNo(order.getOrderNo())
                .userId(user.getId())
                .amount(order.getTotalAmount())
                .currency("CNY")
                .status("SUCCEEDED")
                .channel("MOCK")
                .createdAt(LocalDateTime.now())
                .succeededAt(LocalDateTime.now())
                .build();
    }
}
