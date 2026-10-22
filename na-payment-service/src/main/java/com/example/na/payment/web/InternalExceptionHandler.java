package com.example.na.payment.web;

import com.example.na.payment.contract.PaymentErrorCode;
import com.example.na.payment.shared.PaymentApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class InternalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(InternalExceptionHandler.class);

    @ExceptionHandler(PaymentApiException.class)
    public ResponseEntity<PaymentErrorResponse> handlePaymentApi(PaymentApiException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new PaymentErrorResponse(exception.getCode().name(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<PaymentErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(new PaymentErrorResponse(
                PaymentErrorCode.INVALID_REQUEST.name(), "参数校验失败"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<PaymentErrorResponse> handleBadRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new PaymentErrorResponse(
                PaymentErrorCode.INVALID_REQUEST.name(), "请求参数或格式不正确"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<PaymentErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception) {
        log.warn("Payment data integrity conflict", exception);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new PaymentErrorResponse(
                PaymentErrorCode.PAYMENT_CONFLICT.name(), "数据冲突，请重试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<PaymentErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unexpected payment error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new PaymentErrorResponse(
                PaymentErrorCode.PAYMENT_INTERNAL_ERROR.name(), "支付服务内部错误"));
    }
}
