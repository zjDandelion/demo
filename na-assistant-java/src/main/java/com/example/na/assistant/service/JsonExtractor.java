package com.example.na.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 从模型输出里提取 JSON 对象。
 *
 * <p>与 Python 的 {@code _extract_json} 行为一致，三层兜底：
 * <ol>
 *   <li>整体就是合法 JSON 对象；</li>
 *   <li>去掉 Markdown 代码块围栏（{@code ```} 或 {@code ```json}）；</li>
 *   <li>从第一个 {@code {} 开始按括号配对扫描，正确处理字符串内的括号和转义。</li>
 * </ol>
 *
 * <p>任何一步失败都抛 {@link InvalidOutputException}，由上层映射为 {@code INVALID_OUTPUT} 降级。
 */
public class JsonExtractor {

    private final ObjectMapper objectMapper;

    public JsonExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @throws InvalidOutputException 无法提取出 JSON 对象
     */
    public JsonNode extract(String content) {
        if (content == null) {
            throw new InvalidOutputException("model content is null");
        }
        String text = content.trim();

        // 第 2 层：去代码块围栏
        if (text.startsWith("```")) {
            text = stripBackticks(text);
        }

        // 第 1 层：整体解析
        JsonNode direct = tryParseObject(text);
        if (direct != null) {
            return direct;
        }

        // 第 3 层：括号配对扫描
        JsonNode scanned = scanBalancedObject(text);
        if (scanned != null) {
            return scanned;
        }

        throw new InvalidOutputException("no json object in model output");
    }

    /**
     * 对应 Python 的 {@code text.strip("`")} + 去 {@code json} 前缀。
     *
     * <p>Python 的实现是 {@code text.strip("`")}，只去掉首尾的反引号字符，
     * 然后若以 json 开头（忽略大小写）再去掉这 4 个字符。
     */
    private String stripBackticks(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == '`') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '`') {
            end--;
        }
        String stripped = text.substring(start, end).trim();
        if (stripped.length() >= 4 && stripped.substring(0, 4).equalsIgnoreCase("json")) {
            stripped = stripped.substring(4).trim();
        }
        return stripped;
    }

    private JsonNode tryParseObject(String text) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            if (node != null && node.isObject()) {
                return node;
            }
        } catch (IOException ignored) {
            // 继续尝试下一层
        }
        return null;
    }

    /**
     * 从第一个 {@code {} 起按深度扫描，遇到深度归零即尝试解析该子串。
     *
     * <p>字符串内的 {@code {} / {@code }} 不参与计数；反斜杠转义要跳过下一个字符。
     */
    private JsonNode scanBalancedObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return tryParseObject(text.substring(start, index + 1));
                }
            }
        }
        return null;
    }

    /**
     * 模型输出无法解析。
     */
    public static class InvalidOutputException extends RuntimeException {

        public InvalidOutputException(String message) {
            super(message);
        }
    }
}
