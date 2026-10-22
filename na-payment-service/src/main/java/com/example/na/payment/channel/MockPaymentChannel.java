package com.example.na.payment.channel;

import com.example.na.payment.domain.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentChannel implements PaymentChannel {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentChannel.class);

    private final long delayMs;

    public MockPaymentChannel(@Value("${app.mock.delay-ms:0}") long delayMs) {
        this.delayMs = delayMs;
    }

    @Override
    public void charge(Payment payment) {
        simulateDelay();
        log.info("Mock channel charged: paymentNo={}, orderNo={}, amount={} {}",
                payment.getPaymentNo(), payment.getOrderNo(), payment.getAmount(), payment.getCurrency());
    }

    @Override
    public void refund(Payment payment, String reason) {
        simulateDelay();
        log.info("Mock channel refunded: paymentNo={}, orderNo={}, reason={}",
                payment.getPaymentNo(), payment.getOrderNo(), reason);
    }

    @Override
    public void close(Payment payment, String reason) {
        log.info("Mock channel closed: paymentNo={}, orderNo={}, reason={}",
                payment.getPaymentNo(), payment.getOrderNo(), reason);
    }

    private void simulateDelay() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PaymentChannelException("支付渠道调用被中断", exception);
        }
    }
}
