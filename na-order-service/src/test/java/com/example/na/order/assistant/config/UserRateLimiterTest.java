package com.example.na.order.assistant.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRateLimiterTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @Test
    void allowsUpToLimitWithinWindow() {
        UserRateLimiter limiter = new UserRateLimiter(2, clock::get);

        assertTrue(limiter.tryAcquire("demo"));
        assertTrue(limiter.tryAcquire("demo"));
        assertFalse(limiter.tryAcquire("demo"));
        assertTrue(limiter.tryAcquire("other"));
    }

    @Test
    void resetsInNextWindow() {
        UserRateLimiter limiter = new UserRateLimiter(1, clock::get);

        assertTrue(limiter.tryAcquire("demo"));
        assertFalse(limiter.tryAcquire("demo"));

        clock.addAndGet(60_000L);

        assertTrue(limiter.tryAcquire("demo"));
    }

    @Test
    void nonPositiveLimitDisablesLimiting() {
        UserRateLimiter limiter = new UserRateLimiter(0, clock::get);

        assertTrue(limiter.tryAcquire("demo"));
        assertTrue(limiter.tryAcquire("demo"));
        assertTrue(limiter.tryAcquire("demo"));
    }
}
