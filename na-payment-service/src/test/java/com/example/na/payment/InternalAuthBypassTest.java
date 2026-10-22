package com.example.na.payment;

import com.example.na.payment.contract.InternalHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 走真实 HTTP：只有内嵌 Tomcat 会解码并归一化路径，MockMvc 复现不了这类绕过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InternalAuthBypassTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void pathVariantsWithoutTokenAreRejected() {
        assertUnauthorized("/%69nternal/v1/payments/by-order/ORD-B1?userId=1");
        assertUnauthorized("/%69%6e%74ernal/v1/payments/by-order/ORD-B2?userId=1");
        assertUnauthorized("//internal/v1/payments/by-order/ORD-B3?userId=1");
        assertUnauthorized("/./internal/v1/payments/by-order/ORD-B4?userId=1");
        assertUnauthorized("/internal/v1/payments/by-order/ORD-B5?userId=1");
    }

    @Test
    void wrongTokenIsRejectedOnProtectedPath() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(InternalHeaders.SERVICE_TOKEN, "wrong-token");
        ResponseEntity<String> response = restTemplate.exchange(
                uri("/internal/v1/payments/by-order/ORD-W?userId=1"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertEquals(401, response.getStatusCodeValue());
    }

    @Test
    void healthStaysOpenWithoutToken() {
        ResponseEntity<String> response = restTemplate.getForEntity(uri("/actuator/health"),
                String.class);

        assertEquals(200, response.getStatusCodeValue());
        assertTrue(response.getBody().contains("UP"));
    }

    private void assertUnauthorized(String rawPath) {
        ResponseEntity<String> response = restTemplate.exchange(uri(rawPath), HttpMethod.GET,
                null, String.class);
        assertEquals(401, response.getStatusCodeValue(), () -> rawPath + " -> " + response.getBody());
    }

    private URI uri(String rawPath) {
        return URI.create("http://localhost:" + port + rawPath);
    }
}
