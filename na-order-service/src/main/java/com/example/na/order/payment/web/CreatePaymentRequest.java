package com.example.na.order.payment.web;

import javax.validation.constraints.NotBlank;

public class CreatePaymentRequest {

    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    public CreatePaymentRequest() {
    }

    public CreatePaymentRequest(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }
}
