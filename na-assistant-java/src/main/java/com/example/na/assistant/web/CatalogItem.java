package com.example.na.assistant.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 目录条目。
 *
 * <p>对应 Python 的 {@code CatalogItem}，使用 {@code extra="ignore"}，
 * 因此这里允许未知字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogItem {

    @NotNull
    private Long id;

    @NotBlank
    @Size(max = 200)
    private String name;

    @Size(max = 500)
    private String description;

    @Size(max = 100)
    private String category;

    @Size(max = 500)
    private String tags;

    @NotNull
    private BigDecimal price;

    @NotNull
    private Integer stock;
}
