package com.example.na.order.assistant.service;

import com.example.na.order.assistant.config.AssistantProperties;
import com.example.na.order.assistant.support.AssistantTestCatalog;
import com.example.na.order.catalog.api.CatalogPort;
import com.example.na.order.catalog.api.ProductInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CatalogSnapshotCacheTest {

    private final AssistantProperties properties = new AssistantProperties();

    @Test
    void reloadsOnEveryCallWhenTtlDisabled() {
        properties.setCatalogCacheTtlMs(0);
        CountingCatalogPort port = new CountingCatalogPort();
        CatalogSnapshotCache cache = new CatalogSnapshotCache(port, properties);

        cache.get();
        cache.get();

        assertEquals(2, port.calls);
    }

    @Test
    void reusesSnapshotWithinTtl() {
        properties.setCatalogCacheTtlMs(60_000);
        CountingCatalogPort port = new CountingCatalogPort();
        CatalogSnapshotCache cache = new CatalogSnapshotCache(port, properties);

        List<ProductInfo> first = cache.get();
        List<ProductInfo> second = cache.get();

        assertEquals(1, port.calls);
        assertSame(first, second);
    }

    private static final class CountingCatalogPort implements CatalogPort {

        private int calls;

        @Override
        public List<ProductInfo> listEnabled() {
            calls++;
            return AssistantTestCatalog.products();
        }

        @Override
        public ProductInfo getProduct(Long productId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean deductStock(Long productId, int quantity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void restoreStock(Long productId, int quantity) {
            throw new UnsupportedOperationException();
        }
    }
}
