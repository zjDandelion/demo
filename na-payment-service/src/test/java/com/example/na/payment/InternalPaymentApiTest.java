package com.example.na.payment;

import com.example.na.payment.contract.InternalHeaders;
import com.example.na.payment.domain.Payment;
import com.example.na.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalPaymentApiTest {

    private static final String TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentRepository paymentRepository;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
    }

    @Test
    void s1_internalApiRejectsMissingOrWrongToken() throws Exception {
        mockMvc.perform(post("/internal/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("ORD-S1", 1L, "59.00")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL"));

        mockMvc.perform(post("/internal/v1/payments")
                        .header(InternalHeaders.SERVICE_TOKEN, "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("ORD-S1", 1L, "59.00")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_INTERNAL"));
    }

    @Test
    void internalApiCreatesAndReturnsPaymentIdempotently() throws Exception {
        MvcResult first = mockMvc.perform(post("/internal/v1/payments")
                        .header(InternalHeaders.SERVICE_TOKEN, TOKEN)
                        .header(InternalHeaders.REQUEST_ID, "req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("ORD-API", 1L, "59.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentNo", startsWith("PAY")))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.channel").value("MOCK"))
                .andReturn();

        String paymentNo = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(),
                "$.paymentNo");

        mockMvc.perform(post("/internal/v1/payments")
                        .header(InternalHeaders.SERVICE_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentJson("ORD-API", 1L, "59.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentNo").value(paymentNo));

        mockMvc.perform(get("/internal/v1/payments/{paymentNo}", paymentNo)
                        .header(InternalHeaders.SERVICE_TOKEN, TOKEN)
                        .param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        mockMvc.perform(get("/internal/v1/payments/by-order/{orderNo}", "ORD-API")
                        .header(InternalHeaders.SERVICE_TOKEN, TOKEN)
                        .param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentNo").value(paymentNo));

        mockMvc.perform(post("/internal/v1/payments/{paymentNo}/refund", paymentNo)
                        .header(InternalHeaders.SERVICE_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"user cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        mockMvc.perform(post("/internal/v1/payments/{paymentNo}/refund", paymentNo)
                        .header(InternalHeaders.SERVICE_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"user cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void internalApiCanCloseCreatedPaymentIdempotently() throws Exception {
        Payment created = paymentRepository.saveAndFlush(new Payment(
                "PAY-API-CLOSE", "ORD-API-CLOSE", 1L, new BigDecimal("10.00"), "CNY", "MOCK"));

        mockMvc.perform(post("/internal/v1/payments/{paymentNo}/close", created.getPaymentNo())
                        .header(InternalHeaders.SERVICE_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"order cancelled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        mockMvc.perform(post("/internal/v1/payments/{paymentNo}/close", created.getPaymentNo())
                        .header(InternalHeaders.SERVICE_TOKEN, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void internalApiReturnsStructuredValidationErrors() throws Exception {
        mockMvc.perform(post("/internal/v1/payments")
                        .header(InternalHeaders.SERVICE_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"ORD-BAD\",\"userId\":1,\"amount\":0,\"currency\":\"CNY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/internal/v1/payments")
                        .header(InternalHeaders.SERVICE_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private String paymentJson(String orderNo, Long userId, String amount) {
        return "{\"orderNo\":\"" + orderNo + "\",\"userId\":" + userId
                + ",\"amount\":" + amount + ",\"currency\":\"CNY\"}";
    }
}
