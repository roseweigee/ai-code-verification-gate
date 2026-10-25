package com.demo.service;

import com.demo.dto.ProductResponse;

import java.util.List;

/**
 * 商品服務介面
 */
public interface ProductService {

    /**
     * 查詢所有商品
     *
     * @return 商品清單
     */
    List<ProductResponse> findAllProducts();
}