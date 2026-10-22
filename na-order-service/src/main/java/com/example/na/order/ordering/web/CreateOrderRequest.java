package com.example.na.order.ordering.web;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

public class CreateOrderRequest {

    @NotEmpty(message = "订单至少包含一件商品")
    @Size(max = 20, message = "一次最多购买 20 种商品")
    @Valid
    private List<OrderItemRequest> items = new ArrayList<>();

    public CreateOrderRequest() {
    }

    public CreateOrderRequest(List<OrderItemRequest> items) {
        this.items = items;
    }

    public List<OrderItemRequest> getItems() {
        return items;
    }

    public void setItems(List<OrderItemRequest> items) {
        this.items = items;
    }

    public static class OrderItemRequest {

        @NotNull(message = "商品不能为空")
        private Long productId;

        @NotNull(message = "购买数量不能为空")
        @Min(value = 1, message = "购买数量至少为 1")
        @Max(value = 99, message = "单个商品最多购买 99 件")
        private Integer quantity;

        public OrderItemRequest() {
        }

        public OrderItemRequest(Long productId, Integer quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }

        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }
}
