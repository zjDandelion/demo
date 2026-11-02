package com.example.na.assistant.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型调用用量。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Usage {

    private int promptTokens;

    private int completionTokens;

    private int latencyMs;

    public static Usage empty(int latencyMs) {
        return new Usage(0, 0, latencyMs);
    }
}
