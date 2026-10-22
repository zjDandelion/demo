package com.example.na.order.catalog.api;

import java.util.List;

public interface CatalogPort {

    List<ProductInfo> listEnabled();

    ProductInfo getProduct(Long productId);

    boolean deductStock(Long productId, int quantity);

    void restoreStock(Long productId, int quantity);
}
