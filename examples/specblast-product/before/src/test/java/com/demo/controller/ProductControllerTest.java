package com.demo.controller;

import com.demo.dto.ProductResponse;
import com.demo.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品查詢 Controller 單元測試
 */
@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    @Test
    @DisplayName("查詢所有商品 - 成功")
    void findAllProducts_success() throws Exception {
        // given
        List<ProductResponse> mockProducts = List.of(
                ProductResponse.builder()
                        .productId(1L)
                        .name("商品A")
                        .price(new BigDecimal("100.00"))
                        .build(),
                ProductResponse.builder()
                        .productId(2L)
                        .name("商品B")
                        .price(new BigDecimal("250.00"))
                        .build()
        );
        when(productService.findAllProducts()).thenReturn(mockProducts);

        // when & then
        mockMvc.perform(get("/api/products")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].productId").value(1))
                .andExpect(jsonPath("$.data[0].name").value("商品A"))
                .andExpect(jsonPath("$.data[0].price").value(100.00))
                .andExpect(jsonPath("$.data[1].productId").value(2))
                .andExpect(jsonPath("$.data[1].name").value("商品B"));
    }

    @Test
    @DisplayName("查詢所有商品 - 回傳空清單")
    void findAllProducts_emptyList() throws Exception {
        // given
        when(productService.findAllProducts()).thenReturn(List.of());

        // when & then
        mockMvc.perform(get("/api/products")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}