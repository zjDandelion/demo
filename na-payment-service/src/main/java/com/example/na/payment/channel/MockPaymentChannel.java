package com.example.na.payment.channel;

import com.example.na.payment.domain.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模拟渠道。
 *
 * <p>用内存集合模拟「渠道侧的幂等键记录」：同一个幂等键重复调用时，
 * 渠道不会重复扣款/退款，只记一条日志。真实渠道由渠道方保证，
 * 这里用它来让测试能验证幂等行为（KI-01）。
 */
@Component
public class MockPaymentChannel implements PaymentChannel {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentChannel.class);

    private final long delayMs;

    /** 已处理过的幂等键，模拟渠道侧的幂等表。 */
    private final Set<String> processedKeys = ConcurrentHashMap.newKeySet();

    public MockPaymentChannel(@Value("${app.mock.delay-ms:0}") long delayMs) {
        this.delayMs = delayMs;
    }

    @Override
    public void charge(Payment payment, String idempotencyKey) {
        if (alreadyProcessed("charge", idempotencyKey)) {
            return;
        }
        simulateDelay();
        log.info("Mock channel charged: paymentNo={}, orderNo={}, amount={} {}, idempotencyKey={}",
                payment.getPaymentNo(), payment.getOrderNo(), payment.getAmount(),
                payment.getCurrency(), idempotencyKey);
    }

    @Override
    public void refund(Payment payment, String reason, String idempotencyKey) {
        if (alreadyProcessed("refund", idempotencyKey)) {
            return;
        }
        simulateDelay();
        log.info("Mock channel refunded: paymentNo={}, orderNo={}, reason={}, idempotencyKey={}",
                payment.getPaymentNo(), payment.getOrderNo(), reason, idempotencyKey);
    }

    @Override
    public void close(Payment payment, String reason, String idempotencyKey) {
        if (alreadyProcessed("close", idempotencyKey)) {
            return;
        }
        log.info("Mock channel closed: paymentNo={}, orderNo={}, reason={}, idempotencyKey={}",
                payment.getPaymentNo(), payment.getOrderNo(), reason, idempotencyKey);
    }

    /**
     * 幂等判断：同一个 (操作, 幂等键) 只处理一次。
     */
    private boolean alreadyProcessed(String action, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isEmpty()) {
            // 没有幂等键时不做去重，但记录告警，便于发现调用方漏传
            log.warn("Channel call without idempotency key: action={}, paymentNo={}",
                    action, "unknown");
            return false;
        }
        boolean first = processedKeys.add(action + ":" + idempotencyKey);
        if (!first) {
            log.info("Mock channel skipped duplicate call: action={}, idempotencyKey={}",
                    action, idempotencyKey);
        }
        return !first;
    }

    /** 测试用：清空幂等记录。 */
    public void reset() {
        processedKeys.clear();
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
