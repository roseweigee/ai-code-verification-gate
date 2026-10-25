package com.demo.controller;

import com.demo.common.ApiResponse;
import com.demo.dto.ProductResponse;
import com.demo.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品查詢 Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 查詢所有商品
     *
     * @return 商品清單
     */
    @GetMapping
    public ApiResponse<List<ProductResponse>> findAllProducts() {
        log.info("查詢所有商品");
        List<ProductResponse> products = productService.findAllProducts();
        return ApiResponse.success(products);
    }
}