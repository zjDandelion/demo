package com.example.na.order.paymentclient;

import com.example.na.payment.contract.ClosePaymentCommand;
import com.example.na.payment.contract.InternalHeaders;
import com.example.na.payment.contract.PaymentCommand;
import com.example.na.payment.contract.PaymentStatusView;
import com.example.na.payment.contract.PaymentView;
import com.example.na.payment.contract.RefundCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    private final RestTemplate restTemplate;
    private final PaymentClientProperties properties;
    private final String baseUrl;

    public PaymentClient(@Qualifier("paymentRestTemplate") RestTemplate restTemplate,
                         PaymentClientProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.baseUrl = trimTrailingSlash(properties.getBaseUrl());
    }

    public PaymentView pay(PaymentCommand command) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/internal/v1/payments")
                .build()
                .encode()
                .toUri();
        return exchange("创建支付单", HttpMethod.POST, uri, command, PaymentView.class);
    }

    public PaymentView getPayment(Long userId, String paymentNo) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment("internal", "v1", "payments", paymentNo)
                .queryParam("userId", userId)
                .build()
                .encode()
                .toUri();
        return exchange("查询支付单", HttpMethod.GET, uri, null, PaymentView.class);
    }

    public Optional<PaymentStatusView> findByOrder(Long userId, String orderNo) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment("internal", "v1", "payments", "by-order", orderNo)
                .queryParam("userId", userId)
                .build()
                .encode()
                .toUri();
        try {
            return Optional.ofNullable(exchange("查询订单支付状态", HttpMethod.GET, uri,
                    null, PaymentStatusView.class));
        } catch (PaymentNotFoundException exception) {
            return Optional.empty();
        }
    }

    public PaymentView refund(String paymentNo, String reason) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment("internal", "v1", "payments", paymentNo, "refund")
                .build()
                .encode()
                .toUri();
        return exchange("退款", HttpMethod.POST, uri, new RefundCommand(reason), PaymentView.class);
    }

    public PaymentView close(String paymentNo, String reason) {
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment("internal", "v1", "payments", paymentNo, "close")
                .build()
                .encode()
                .toUri();
        return exchange("关闭支付单", HttpMethod.POST, uri, new ClosePaymentCommand(reason),
                PaymentView.class);
    }

    private <T> T exchange(String description, HttpMethod method, URI uri, Object body,
                           Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set(InternalHeaders.SERVICE_TOKEN,
                properties.getServiceToken() == null ? "" : properties.getServiceToken());
        String requestId = UUID.randomUUID().toString();
        headers.set(InternalHeaders.REQUEST_ID, requestId);

        try {
            ResponseEntity<T> response = restTemplate.exchange(uri, method,
                    new HttpEntity<>(body, headers), responseType);
            return response.getBody();
        } catch (HttpStatusCodeException exception) {
            HttpStatus status = exception.getStatusCode();
            log.warn("Payment service call failed: action={}, requestId={}, status={}",
                    description, requestId, status.value());
            if (status == HttpStatus.NOT_FOUND) {
                throw new PaymentNotFoundException("支付单不存在");
            }
            if (status == HttpStatus.CONFLICT) {
                throw new PaymentConflictException("支付请求冲突");
            }
            if (status == HttpStatus.BAD_REQUEST) {
                throw new PaymentClientException(status.value(), "支付请求不合法");
            }
            if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
                throw new PaymentUnavailableException("内部服务认证失败");
            }
            throw new PaymentUnavailableException("支付服务暂时不可用");
        } catch (ResourceAccessException exception) {
            log.warn("Payment service unreachable: action={}, requestId={}, error={}",
                    description, requestId, exception.getMessage());
            throw new PaymentUnavailableException("支付服务暂时不可用");
        } catch (RestClientException exception) {
            log.warn("Payment service call error: action={}, requestId={}, error={}",
                    description, requestId, exception.getMessage());
            throw new PaymentUnavailableException("支付服务调用失败");
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
