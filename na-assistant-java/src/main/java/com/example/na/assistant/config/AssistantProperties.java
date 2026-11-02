package com.example.na.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 助手服务配置。
 *
 * <p>环境变量名与 Python 服务保持一致（{@code ASSISTANT_*}），便于共用 .env。
 */
@Data
@ConfigurationProperties(prefix = "app.assistant")
public class AssistantProperties {

    /** 内部鉴权 token，为空时拒绝所有内部请求。 */
    private String internalToken = "";

    /** OpenAI 兼容端点根地址。 */
    private String baseUrl = "";

    private String model = "";

    private String apiKey = "";

    /** 上游调用超时，毫秒。 */
    private int timeoutMs = 4000;

    /** 请求体大小上限，字节。 */
    private int maxBodyBytes = 262144;

    /** 目录条数上限。 */
    private int maxCatalog = 100;

    /** 排除列表条数上限。 */
    private int maxExclude = 100;

    /** 用户文本长度上限。 */
    private int maxTextLength = 2000;

    private String debug = "0";

    /**
     * 三项都配置才会调用模型，否则返回 DISABLED。
     */
    public boolean isModelConfigured() {
        return notBlank(baseUrl) && notBlank(model) && notBlank(apiKey);
    }

    /**
     * 超时秒数，最小值 0.1 秒（与 Python 的 {@code max(timeout, 100)/1000} 对齐）。
     */
    public long getTimeoutMillis() {
        return Math.max(timeoutMs, 100);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
