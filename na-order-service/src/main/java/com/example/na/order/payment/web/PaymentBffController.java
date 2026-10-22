package com.example.na.order.payment.web;

import com.example.na.order.payment.service.OrderPaymentOrchestrator;
import com.example.na.order.user.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "支付")
public class PaymentBffController {

    private final OrderPaymentOrchestrator orderPaymentOrchestrator;

    @PostMapping
    @Operation(summary = "支付订单（Mock 渠道，同步成功）")
    public ResponseEntity<PaymentResponse> pay(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreatePaymentRequest request) {
        OrderPaymentOrchestrator.PaymentResult result =
                orderPaymentOrchestrator.pay(user.getUserId(), request.getOrderNo());
        HttpStatus status = result.isCreated() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.getPayment());
    }

    @GetMapping("/{paymentNo}")
    @Operation(summary = "支付单详情")
    public PaymentResponse detail(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String paymentNo) {
        return orderPaymentOrchestrator.getPayment(user.getUserId(), paymentNo);
    }
}
