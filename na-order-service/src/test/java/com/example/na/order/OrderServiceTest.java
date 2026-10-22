package com.example.na.order.ordering;

import com.example.na.order.catalog.domain.Product;
import com.example.na.order.catalog.repository.ProductRepository;
import com.example.na.order.ordering.domain.OrderStatus;
import com.example.na.order.ordering.repository.OrderRepository;
import com.example.na.order.ordering.service.OrderService;
import com.example.na.order.ordering.web.CreateOrderRequest;
import com.example.na.order.ordering.web.CreateOrderRequest.OrderItemRequest;
import com.example.na.order.ordering.web.OrderItemResponse;
import com.example.na.order.ordering.web.OrderResponse;
import com.example.na.order.paymentattempt.repository.OrderPaymentAttemptRepository;
import com.example.na.order.shared.BusinessException;
import com.example.na.order.user.domain.User;
import com.example.na.order.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class OrderServiceTest {

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

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        attemptRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Test
    void t1_createOrderDeductsStockAndSnapshotsPrice() {
        Product keyboard = createProduct("机械键盘", "299.00", 10);
        Product mouse = createProduct("无线鼠标", "129.00", 5);
        User user = createUser("alice");

        OrderResponse order = orderService.createOrder(user.getId(), request(
                item(keyboard.getId(), 2), item(mouse.getId(), 1)));

        assertEquals(OrderStatus.PENDING_PAYMENT, order.getStatus());
        assertEquals(new BigDecimal("727.00"), order.getTotalAmount());
        assertEquals(2, order.getItems().size());
        assertEquals(8, productRepository.findById(keyboard.getId()).get().getStock());
        assertEquals(4, productRepository.findById(mouse.getId()).get().getStock());
        assertEquals(1, orderRepository.countByUserId(user.getId()));

        OrderItemResponse keyboardItem = findItem(order.getItems(), keyboard.getId());
        assertEquals("机械键盘", keyboardItem.getProductName());
        assertEquals(new BigDecimal("299.00"), keyboardItem.getUnitPrice());
        assertEquals(2, keyboardItem.getQuantity());
        assertEquals(new BigDecimal("598.00"), keyboardItem.getSubtotal());
        assertNotNull(order.getCreatedAt());
    }

    @Test
    void t2_insufficientStockRollsBackWholeOrder() {
        Product available = createProduct("有库存商品", "10.00", 5);
        Product soldOut = createProduct("无库存商品", "20.00", 0);
        User user = createUser("bob");

        BusinessException exception = assertThrows(BusinessException.class, () ->
                orderService.createOrder(user.getId(), request(
                        item(available.getId(), 1), item(soldOut.getId(), 1))));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(5, productRepository.findById(available.getId()).get().getStock());
        assertEquals(0, productRepository.findById(soldOut.getId()).get().getStock());
        assertEquals(0, orderRepository.countByUserId(user.getId()));
    }

    @Test
    void t3_cancelOrderRestoresStock() {
        Product product = createProduct("可取消商品", "59.00", 5);
        User user = createUser("carol");
        OrderResponse order = orderService.createOrder(user.getId(), request(item(product.getId(), 2)));
        assertEquals(3, productRepository.findById(product.getId()).get().getStock());

        OrderResponse cancelled = orderService.cancelOrder(user.getId(), order.getOrderNo());

        assertEquals(OrderStatus.CANCELLED, cancelled.getStatus());
        assertNotNull(cancelled.getCancelledAt());
        assertEquals(5, productRepository.findById(product.getId()).get().getStock());
    }

    @Test
    void t4_repeatedCancelDoesNotRestoreTwice() {
        Product product = createProduct("重复取消商品", "59.00", 5);
        User user = createUser("dave");
        OrderResponse order = orderService.createOrder(user.getId(), request(item(product.getId(), 2)));
        orderService.cancelOrder(user.getId(), order.getOrderNo());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> orderService.cancelOrder(user.getId(), order.getOrderNo()));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals(5, productRepository.findById(product.getId()).get().getStock());
        assertEquals(OrderStatus.CANCELLED, orderRepository.findByOrderNoAndUserId(order.getOrderNo(), user.getId())
                .get().getStatus());
    }

    @Test
    void t8_otherUsersOrderIsNotFound() {
        Product product = createProduct("越权测试商品", "10.00", 5);
        User owner = createUser("owner");
        User stranger = createUser("stranger");
        OrderResponse order = orderService.createOrder(owner.getId(), request(item(product.getId(), 1)));

        BusinessException detailException = assertThrows(BusinessException.class,
                () -> orderService.getOrder(stranger.getId(), order.getOrderNo()));
        assertEquals(HttpStatus.NOT_FOUND, detailException.getStatus());

        BusinessException cancelException = assertThrows(BusinessException.class,
                () -> orderService.cancelOrder(stranger.getId(), order.getOrderNo()));
        assertEquals(HttpStatus.NOT_FOUND, cancelException.getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT,
                orderRepository.findByOrderNoAndUserId(order.getOrderNo(), owner.getId()).get().getStatus());
    }

    @Test
    void t9_concurrentOrdersDoNotOversell() throws Exception {
        Product lastOne = createProduct("最后一件", "59.00", 1);
        User user = createUser("racer");
        int threads = 10;

        executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Callable<Boolean> task = () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try {
                    orderService.createOrder(user.getId(), request(item(lastOne.getId(), 1)));
                    return true;
                } catch (BusinessException exception) {
                    assertEquals(HttpStatus.CONFLICT, exception.getStatus());
                    return false;
                }
            };
            futures.add(executor.submit(task));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "all threads should be ready");
        start.countDown();

        int success = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                success++;
            }
        }

        assertEquals(1, success, "exactly one order should succeed");
        assertEquals(0, productRepository.findById(lastOne.getId()).get().getStock());
        assertEquals(1, orderRepository.countByUserId(user.getId()));
    }

    private Product createProduct(String name, String price, int stock) {
        return productRepository.save(new Product(name, name + " 描述", new BigDecimal(price), stock, true));
    }

    private User createUser(String username) {
        return userRepository.save(new User(username, "not-used-in-test", username));
    }

    private CreateOrderRequest request(OrderItemRequest... items) {
        return new CreateOrderRequest(new ArrayList<>(Arrays.asList(items)));
    }

    private OrderItemRequest item(Long productId, int quantity) {
        return new OrderItemRequest(productId, quantity);
    }

    private OrderItemResponse findItem(List<OrderItemResponse> items, Long productId) {
        for (OrderItemResponse item : items) {
            if (item.getProductId().equals(productId)) {
                return item;
            }
        }
        throw new AssertionError("item not found: " + productId);
    }
}
