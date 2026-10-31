package com.example.na.payment.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 退款命令。
 *
 * <p>{@code userId} 用于服务端校验支付单归属（KI-03）。调用方必须传入，
 * 支付服务会拒绝与支付单归属不符的请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundCommand implements Serializable {

    private String reason;

    private Long userId;

    public RefundCommand(String reason) {
        this.reason = reason;
    }
}
