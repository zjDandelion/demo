package com.example.na.order.paymentclient;

import com.example.na.payment.contract.InternalHeaders;
import com.example.na.payment.contract.PaymentCommand;
import com.example.na.payment.contract.PaymentStatusView;
import com.example.na.payment.contract.PaymentView;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaymentClientTest {

    private RestTemplate restTemplate;

    private MockRestServiceServer server;

    private PaymentClient client;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        restTemplate = new RestTemplate();
        restTemplate.getMessageConverters().add(0,
                new MappingJackson2HttpMessageConverter(mapper));
        server = MockRestServiceServer.bindTo(restTemplate).build();

        PaymentClientProperties properties = new PaymentClientProperties();
        properties.setBaseUrl("http://localhost:9999/");
        properties.setServiceToken("test-token");
        client = new PaymentClient(restTemplate, properties);
    }

    @Test
    void paySendsServiceHeadersAndParsesResponse() {
        server.expect(requestTo("http://localhost:9999/internal/v1/payments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(InternalHeaders.SERVICE_TOKEN, "test-token"))
                .andExpect(header(InternalHeaders.REQUEST_ID, notNullValue()))
                .andExpect(jsonPath("$.orderNo").value("ORD-1"))
                .andExpect(jsonPath("$.amount").value(10.00))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(paymentJson("SUCCEEDED")));

        PaymentView result = client.pay(PaymentCommand.builder()
                .orderNo("ORD-1")
                .userId(7L)
                .amount(new BigDecimal("10.00"))
                .currency("CNY")
                .build());

        assertEquals("PAY-1", result.getPaymentNo());
        assertEquals("SUCCEEDED", result.getStatus());
        assertEquals(new BigDecimal("10.00"), result.getAmount());
        server.verify();
    }

    @Test
    void missingPaymentMapsToNotFound() {
        server.expect(requestTo("http://localhost:9999/internal/v1/payments/PAY-1?userId=7"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PAYMENT_NOT_FOUND\",\"message\":\"not found\"}"));

        assertThrows(PaymentNotFoundException.class, () -> client.getPayment(7L, "PAY-1"));
    }

    @Test
    void conflictAndUnavailableAreMapped() {
        server.expect(requestTo("http://localhost:9999/internal/v1/payments"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PAYMENT_CONFLICT\",\"message\":\"conflict\"}"));
        assertThrows(PaymentConflictException.class, () -> client.pay(command()));

        server.reset();
        server.expect(requestTo("http://localhost:9999/internal/v1/payments"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PAYMENT_IN_PROGRESS\",\"message\":\"unknown\"}"));
        assertThrows(PaymentUnavailableException.class, () -> client.pay(command()));
    }

    @Test
    void findByOrderReturnsEmptyOnNotFound() {
        server.expect(requestTo(
                        "http://localhost:9999/internal/v1/payments/by-order/ORD-1?userId=7"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        Optional<PaymentStatusView> result = client.findByOrder(7L, "ORD-1");

        assertFalse(result.isPresent());
    }

    @Test
    void findByOrderParsesStatusView() {
        server.expect(requestTo(
                        "http://localhost:9999/internal/v1/payments/by-order/ORD-1?userId=7"))
                .andRespond(withSuccess("{\"paymentNo\":\"PAY-1\",\"orderNo\":\"ORD-1\","
                        + "\"userId\":7,\"amount\":10.00,\"currency\":\"CNY\","
                        + "\"status\":\"SUCCEEDED\"}", MediaType.APPLICATION_JSON));

        Optional<PaymentStatusView> result = client.findByOrder(7L, "ORD-1");

        assertTrue(result.isPresent());
        assertEquals("PAY-1", result.get().getPaymentNo());
        assertEquals("SUCCEEDED", result.get().getStatus());
    }

    @Test
    void refundPostsReasonAndParsesResponse() {
        server.expect(requestTo("http://localhost:9999/internal/v1/payments/PAY-1/refund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reason").value("测试退款"))
                .andRespond(withSuccess(paymentJson("REFUNDED"), MediaType.APPLICATION_JSON));

        PaymentView result = client.refund("PAY-1", "测试退款");

        assertEquals("REFUNDED", result.getStatus());
    }

    private PaymentCommand command() {
        return PaymentCommand.builder()
                .orderNo("ORD-1")
                .userId(7L)
                .amount(new BigDecimal("10.00"))
                .currency("CNY")
                .build();
    }

    private String paymentJson(String status) {
        return "{\"paymentNo\":\"PAY-1\",\"orderNo\":\"ORD-1\",\"userId\":7,"
                + "\"amount\":10.00,\"currency\":\"CNY\",\"status\":\"" + status + "\","
                + "\"channel\":\"MOCK\",\"createdAt\":\"2026-10-22T12:00:00\","
                + "\"succeededAt\":\"2026-10-22T12:00:01\"}";
    }
}
