package com.example.na.order.catalog.service;

import com.example.na.order.catalog.api.CatalogPort;
import com.example.na.order.catalog.api.ProductInfo;
import com.example.na.order.catalog.domain.Product;
import com.example.na.order.catalog.repository.ProductRepository;
import com.example.na.order.shared.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService implements CatalogPort {

    private final ProductRepository productRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ProductInfo> listEnabled() {
        List<ProductInfo> result = new ArrayList<>();
        for (Product product : productRepository.findByEnabledTrueOrderByIdAsc()) {
            result.add(toInfo(product));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductInfo getProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> BusinessException.notFound("商品不存在"));
        return toInfo(product);
    }

    @Override
    @Transactional
    public boolean deductStock(Long productId, int quantity) {
        return productRepository.deductStock(productId, quantity) == 1;
    }

    @Override
    @Transactional
    public void restoreStock(Long productId, int quantity) {
        productRepository.restoreStock(productId, quantity);
    }

    private ProductInfo toInfo(Product product) {
        return new ProductInfo(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getStock(), product.isEnabled());
    }
}
