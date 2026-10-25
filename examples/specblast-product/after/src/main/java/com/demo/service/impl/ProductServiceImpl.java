package com.demo.service.impl;

import com.demo.dto.ProductResponse;
import com.demo.entity.Product;
import com.demo.repository.ProductRepository;
import com.demo.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 商品服務實作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

    /**
     * 查詢所有商品
     *
     * @return 商品清單
     */
    @Override
    @Transactional(readOnly = true)
    public List<ProductResponse> findAllProducts() {
        // 例外交給 GlobalExceptionHandler：在伺服器端記錄原因，對外只回固定訊息（不洩漏內部細節）
        List<Product> products = productRepository.findAll();
        return products.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 將 Entity 轉換為 Response DTO
     *
     * @param product 商品實體
     * @return 商品回應 DTO
     */
    private ProductResponse toResponse(Product product) {
        return ProductResponse.builder()
                .productId(product.getProductId())
                .name(product.getName())
                .price(product.getPrice())
                .build();
    }
}