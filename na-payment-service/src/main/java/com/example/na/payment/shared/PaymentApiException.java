package com.example.na.payment.shared;

import com.example.na.payment.contract.PaymentErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class PaymentApiException extends RuntimeException {

    private final PaymentErrorCode code;

    private final HttpStatus status;

    public PaymentApiException(PaymentErrorCode code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static PaymentApiException invalid(String message) {
        return new PaymentApiException(PaymentErrorCode.INVALID_REQUEST, HttpStatus.BAD_REQUEST, message);
    }

    public static PaymentApiException notFound(String message) {
        return new PaymentApiException(PaymentErrorCode.PAYMENT_NOT_FOUND, HttpStatus.NOT_FOUND, message);
    }

    public static PaymentApiException conflict(String message) {
        return new PaymentApiException(PaymentErrorCode.PAYMENT_CONFLICT, HttpStatus.CONFLICT, message);
    }

    public static PaymentApiException inProgress(String message) {
        return new PaymentApiException(PaymentErrorCode.PAYMENT_IN_PROGRESS, HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public static PaymentApiException refundNotAllowed(String message) {
        return new PaymentApiException(PaymentErrorCode.REFUND_NOT_ALLOWED, HttpStatus.CONFLICT, message);
    }

    public static PaymentApiException internal(String message) {
        return new PaymentApiException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
