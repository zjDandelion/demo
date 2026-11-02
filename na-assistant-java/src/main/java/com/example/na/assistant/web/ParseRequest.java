package com.example.na.assistant.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析请求。
 *
 * <p>对应 Python 的 {@code ParseRequest}，使用 {@code extra="forbid"}：
 * **未知字段必须拒绝**。这里刻意不加 {@code @JsonIgnoreProperties}，
 * 依靠全局 {@code FAIL_ON_UNKNOWN_PROPERTIES=true} 生效。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParseRequest {

    @NotBlank
    @Size(min = 1, max = 64)
    private String requestId;

    private Long userId;

    @NotBlank
    @Size(min = 1, max = 4000)
    private String text;

    @Valid
    private List<Long> excludeProductIds = new ArrayList<>();

    @Valid
    private List<CatalogItem> catalog = new ArrayList<>();
}
