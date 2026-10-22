package com.example.na.order.catalog.web;

import com.example.na.order.catalog.api.CatalogPort;
import com.example.na.order.catalog.api.ProductInfo;
import com.example.na.order.shared.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "商品")
public class ProductController {

    private final CatalogPort catalogPort;

    @GetMapping
    @Operation(summary = "商品列表")
    public List<ProductView> list() {
        List<ProductView> result = new ArrayList<>();
        for (ProductInfo product : catalogPort.listEnabled()) {
            result.add(toView(product));
        }
        return result;
    }

    @GetMapping("/{id}")
    @Operation(summary = "商品详情")
    public ProductView detail(@PathVariable Long id) {
        ProductInfo product = catalogPort.getProduct(id);
        if (!product.isEnabled()) {
            throw BusinessException.notFound("商品不存在");
        }
        return toView(product);
    }

    private ProductView toView(ProductInfo product) {
        return new ProductView(product.getId(), product.getName(), product.getDescription(),
                product.getPrice(), product.getStock(), product.isEnabled());
    }
}
