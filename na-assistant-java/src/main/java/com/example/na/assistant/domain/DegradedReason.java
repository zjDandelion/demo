package com.example.na.assistant.domain;

/**
 * 降级原因。
 *
 * <p>取值必须与 Python 的 {@code degradedReason} 完全一致，
 * 订单侧的 {@code DegradedReason} 枚举按名字匹配。
 */
public enum DegradedReason {

    /** 未配置 baseUrl / model / apiKey 中的任意一项。 */
    DISABLED,

    /** 调用上游超时。 */
    TIMEOUT,

    /** 上游返回 4xx/5xx，或网络错误。 */
    UPSTREAM_ERROR,

    /** 模型输出无法解析为预期 JSON 结构。 */
    INVALID_OUTPUT
}
