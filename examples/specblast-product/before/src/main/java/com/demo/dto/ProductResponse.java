package com.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 商品回應 DTO
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {

    /** 商品 ID */
    private Long productId;

    /** 商品名稱 */
    private String name;

    /** 商品價格 */
    private BigDecimal price;
}