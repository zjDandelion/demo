package com.example.na.order.ordering.service;

import com.example.na.order.catalog.api.CatalogPort;
import com.example.na.order.catalog.api.ProductInfo;
import com.example.na.order.ordering.domain.CustomerOrder;
import com.example.na.order.ordering.domain.OrderItem;
import com.example.na.order.ordering.domain.OrderStatus;
import com.example.na.order.ordering.repository.OrderRepository;
import com.example.na.order.ordering.web.CreateOrderRequest;
import com.example.na.order.ordering.web.OrderItemResponse;
import com.example.na.order.ordering.web.OrderResponse;
import com.example.na.order.ordering.web.OrderSummaryResponse;
import com.example.na.order.shared.BusinessException;
import com.example.na.order.shared.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final int MAX_SKUS = 20;
    private static final int MAX_ITEM_QUANTITY = 99;

    private final OrderRepository orderRepository;
    private final CatalogPort catalogPort;
    private final OrderNoGenerator orderNoGenerator;

    @Transactional
    public OrderResponse createOrder(Long userId, CreateOrderRequest request) {
        Map<Long, Integer> quantities = mergeItems(request);

        List<OrderLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            ProductInfo product = catalogPort.getProduct(entry.getKey());
            if (!product.isEnabled()) {
                throw BusinessException.conflict("商品已下架：" + product.getName());
            }
            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(entry.getValue()));
            lines.add(new OrderLine(product, entry.getValue(), subtotal));
            total = total.add(subtotal);
        }
        total = total.setScale(2, RoundingMode.HALF_UP);

        for (OrderLine line : lines) {
            if (!catalogPort.deductStock(line.product.getId(), line.quantity)) {
                throw BusinessException.conflict("库存不足：" + line.product.getName());
            }
        }

        CustomerOrder order = new CustomerOrder(orderNoGenerator.nextOrderNo(), userId, total,
                OrderStatus.PENDING_PAYMENT);
        for (OrderLine line : lines) {
            order.addItem(new OrderItem(line.product.getId(), line.product.getName(),
                    line.product.getPrice(), line.quantity));
        }
        orderRepository.saveAndFlush(order);
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> listOrders(Long userId, String status, int page, int size) {
        if (page < 0) {
            throw BusinessException.badRequest("页码不能小于 0");
        }
        if (size < 1 || size > 50) {
            throw BusinessException.badRequest("每页数量必须在 1 到 50 之间");
        }
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<CustomerOrder> orders;
        if (status == null || status.trim().isEmpty()) {
            orders = orderRepository.findByUserId(userId, pageable);
        } else {
            orders = orderRepository.findByUserIdAndStatus(userId, parseStatus(status), pageable);
        }

        List<OrderSummaryResponse> content = new ArrayList<>();
        for (CustomerOrder order : orders.getContent()) {
            content.add(new OrderSummaryResponse(order.getOrderNo(), order.getStatus(), order.getTotalAmount(),
                    order.getCreatedAt(), order.getItems().size()));
        }
        return new PageResponse<>(content, orders.getNumber(), orders.getSize(),
                orders.getTotalElements(), orders.getTotalPages());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long userId, String orderNo) {
        CustomerOrder order = orderRepository.findByOrderNoAndUserId(orderNo, userId)
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        return toResponse(order);
    }

    @Transactional
    public OrderResponse cancelOrder(Long userId, String orderNo) {
        if (!orderRepository.existsByOrderNoAndUserId(orderNo, userId)) {
            throw BusinessException.notFound("订单不存在");
        }
        int updated = orderRepository.cancel(orderNo, userId, OrderStatus.CANCELLED,
                OrderStatus.PENDING_PAYMENT, LocalDateTime.now());
        if (updated == 0) {
            throw BusinessException.conflict("订单当前状态不可取消");
        }

        CustomerOrder order = orderRepository.findByOrderNoAndUserId(orderNo, userId)
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        List<RestoreLine> restoreLines = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            restoreLines.add(new RestoreLine(item.getProductId(), item.getQuantity()));
        }
        for (RestoreLine line : restoreLines) {
            catalogPort.restoreStock(line.productId, line.quantity);
        }
        return toResponse(order);
    }

    private Map<Long, Integer> mergeItems(CreateOrderRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw BusinessException.badRequest("订单至少包含一件商品");
        }
        Map<Long, Integer> quantities = new TreeMap<>();
        for (CreateOrderRequest.OrderItemRequest item : request.getItems()) {
            if (item == null || item.getProductId() == null) {
                throw BusinessException.badRequest("商品不能为空");
            }
            if (item.getQuantity() == null || item.getQuantity() < 1 || item.getQuantity() > MAX_ITEM_QUANTITY) {
                throw BusinessException.badRequest("单个商品购买数量必须在 1 到 99 之间");
            }
            int merged = quantities.getOrDefault(item.getProductId(), 0) + item.getQuantity();
            if (merged > MAX_ITEM_QUANTITY) {
                throw BusinessException.badRequest("同一商品购买数量不能超过 99");
            }
            quantities.put(item.getProductId(), merged);
        }
        if (quantities.size() > MAX_SKUS) {
            throw BusinessException.badRequest("一次最多购买 20 种商品");
        }
        return quantities;
    }

    private OrderStatus parseStatus(String status) {
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("订单状态不合法");
        }
    }

    private OrderResponse toResponse(CustomerOrder order) {
        List<OrderItemResponse> items = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            items.add(new OrderItemResponse(item.getProductId(), item.getProductName(), item.getUnitPrice(),
                    item.getQuantity(), item.getSubtotal()));
        }
        return new OrderResponse(order.getOrderNo(), order.getStatus(), order.getTotalAmount(),
                order.getCreatedAt(), order.getPaidAt(), order.getCancelledAt(), items);
    }

    private static final class OrderLine {
        private final ProductInfo product;
        private final int quantity;
        private final BigDecimal subtotal;

        private OrderLine(ProductInfo product, int quantity, BigDecimal subtotal) {
            this.product = product;
            this.quantity = quantity;
            this.subtotal = subtotal;
        }
    }

    private static final class RestoreLine {
        private final Long productId;
        private final int quantity;

        private RestoreLine(Long productId, int quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }
    }
}
