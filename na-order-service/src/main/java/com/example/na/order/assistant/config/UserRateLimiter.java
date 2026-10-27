package com.example.na.order.assistant.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

@Component
public class UserRateLimiter {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_KEYS = 1000;

    private final int limitPerMinute;
    private final LongSupplier clock;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public UserRateLimiter(AssistantProperties properties) {
        this(properties.getRateLimitPerMinute(), System::currentTimeMillis);
    }

    UserRateLimiter(int limitPerMinute, LongSupplier clock) {
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
    }

    public boolean tryAcquire(String key) {
        if (limitPerMinute <= 0) {
            return true;
        }
        long now = clock.getAsLong();
        long windowStart = now - (now % WINDOW_MS);
        Window window = windows.compute(key, (ignored, existing) ->
                existing == null || existing.windowStart != windowStart
                        ? new Window(windowStart, 1)
                        : new Window(windowStart, existing.count + 1));
        if (windows.size() > MAX_KEYS) {
            windows.entrySet().removeIf(entry -> entry.getValue().windowStart < windowStart);
        }
        return window.count <= limitPerMinute;
    }

    private static final class Window {

        private final long windowStart;
        private final int count;

        private Window(long windowStart, int count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
