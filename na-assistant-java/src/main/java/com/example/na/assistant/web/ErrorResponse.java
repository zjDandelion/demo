package com.example.na.assistant.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 错误响应。
 *
 * <p>字段名是 {@code message}（不是 error），与 Python 服务保持一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    private String message;
}
