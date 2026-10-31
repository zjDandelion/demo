package com.example.na.payment.web;

import com.example.na.payment.contract.ClosePaymentCommand;
import com.example.na.payment.contract.InternalHeaders;
import com.example.na.payment.contract.PaymentCommand;
import com.example.na.payment.contract.PaymentStatusView;
import com.example.na.payment.contract.PaymentView;
import com.example.na.payment.contract.RefundCommand;
import com.example.na.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private static final Logger log = LoggerFactory.getLogger(InternalPaymentController.class);

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentView> create(@RequestBody PaymentCommand command,
                                              @RequestHeader(value = InternalHeaders.REQUEST_ID,
                                                      required = false) String requestId) {
        log.info("Internal payment request: requestId={}, orderNo={}", requestId, command.getOrderNo());
        PaymentService.PaymentResult result = paymentService.pay(command);
        HttpStatus status = result.isCreated() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.getPayment());
    }

    @GetMapping("/{paymentNo}")
    public PaymentView get(@PathVariable String paymentNo, @RequestParam Long userId,
                           @RequestHeader(value = InternalHeaders.REQUEST_ID,
                                   required = false) String requestId) {
        log.info("Internal payment query: requestId={}, paymentNo={}", requestId, paymentNo);
        return paymentService.getPayment(userId, paymentNo);
    }

    @GetMapping("/by-order/{orderNo}")
    public PaymentStatusView getByOrder(@PathVariable String orderNo, @RequestParam Long userId,
                                        @RequestHeader(value = InternalHeaders.REQUEST_ID,
                                                required = false) String requestId) {
        log.info("Internal payment query by order: requestId={}, orderNo={}", requestId, orderNo);
        return paymentService.getPaymentByOrder(userId, orderNo);
    }

    @PostMapping("/{paymentNo}/refund")
    public PaymentView refund(@PathVariable String paymentNo,
                              @RequestBody(required = false) RefundCommand command,
                              @RequestHeader(value = InternalHeaders.REQUEST_ID,
                                      required = false) String requestId) {
        String reason = command == null ? null : command.getReason();
        Long userId = command == null ? null : command.getUserId();
        log.info("Internal refund request: requestId={}, paymentNo={}, userId={}",
                requestId, paymentNo, userId);
        return paymentService.refund(paymentNo, userId, reason);
    }

    @PostMapping("/{paymentNo}/close")
    public PaymentView close(@PathVariable String paymentNo,
                             @RequestBody(required = false) ClosePaymentCommand command,
                             @RequestHeader(value = InternalHeaders.REQUEST_ID,
                                     required = false) String requestId) {
        String reason = command == null ? null : command.getReason();
        Long userId = command == null ? null : command.getUserId();
        log.info("Internal close request: requestId={}, paymentNo={}, userId={}",
                requestId, paymentNo, userId);
        return paymentService.close(paymentNo, userId, reason);
    }
}
