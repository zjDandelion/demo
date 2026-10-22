package com.example.na.order.ordering.web;

import com.example.na.order.ordering.service.OrderService;
import com.example.na.order.shared.PageResponse;
import com.example.na.order.user.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "订单")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建订单")
    public OrderResponse create(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
                                @Valid @RequestBody CreateOrderRequest request) {
        return orderService.createOrder(user.getUserId(), request);
    }

    @GetMapping
    @Operation(summary = "我的订单列表")
    public PageResponse<OrderSummaryResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "10") int size) {
        return orderService.listOrders(user.getUserId(), status, page, size);
    }

    @GetMapping("/{orderNo}")
    @Operation(summary = "订单详情")
    public OrderResponse detail(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
                                @PathVariable String orderNo) {
        return orderService.getOrder(user.getUserId(), orderNo);
    }

    @PostMapping("/{orderNo}/cancel")
    @Operation(summary = "取消订单")
    public OrderResponse cancel(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
                                @PathVariable String orderNo) {
        return orderService.cancelOrder(user.getUserId(), orderNo);
    }
}
