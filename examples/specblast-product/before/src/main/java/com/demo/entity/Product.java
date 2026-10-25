package com.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 商品實體
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "products")
public class Product {

    /** 商品主鍵，自動遞增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** 商品名稱 */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** 商品價格 */
    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;
}