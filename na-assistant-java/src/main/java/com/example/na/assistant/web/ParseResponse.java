package com.example.na.assistant.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析响应。
 *
 * <p>对应 Python 的 {@code ParseResponse}。注意 {@code reason} 只在降级时出现，
 * 正常响应里为 null，序列化时会被省略（与 Python 的 non_null 行为一致）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ParseResponse {

    public static final String STATUS_OK = "OK";

    public static final String STATUS_DEGRADED = "DEGRADED";

    private String status;

    private String reason;

    private List<ParsedItem> items = new ArrayList<>();

    private List<ParsedUnmatched> unmatched = new ArrayList<>();

    private List<ParsedQuestion> questions = new ArrayList<>();

    private Usage usage = new Usage();

    public boolean isSuccess() {
        return STATUS_OK.equals(status);
    }
}
