package com.example.na.order.assistant.service;

import com.example.na.order.assistant.config.AssistantProperties;
import com.example.na.order.catalog.api.CatalogPort;
import com.example.na.order.catalog.api.ProductInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CatalogSnapshotCache {

    private final CatalogPort catalogPort;
    private final AssistantProperties properties;

    private volatile Snapshot snapshot;

    public List<ProductInfo> get() {
        long now = System.currentTimeMillis();
        Snapshot current = snapshot;
        if (isValid(current, now)) {
            return current.products;
        }
        synchronized (this) {
            current = snapshot;
            now = System.currentTimeMillis();
            if (isValid(current, now)) {
                return current.products;
            }
            List<ProductInfo> loaded = Collections.unmodifiableList(
                    new ArrayList<>(catalogPort.listEnabled()));
            snapshot = new Snapshot(loaded, now);
            return loaded;
        }
    }

    private boolean isValid(Snapshot candidate, long now) {
        long ttl = properties.getCatalogCacheTtlMs();
        return ttl > 0 && candidate != null && now - candidate.loadedAt < ttl;
    }

    private static final class Snapshot {

        private final List<ProductInfo> products;
        private final long loadedAt;

        private Snapshot(List<ProductInfo> products, long loadedAt) {
            this.products = products;
            this.loadedAt = loadedAt;
        }
    }
}
