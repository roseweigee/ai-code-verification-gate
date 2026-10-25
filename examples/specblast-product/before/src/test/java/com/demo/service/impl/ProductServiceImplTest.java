package com.demo.service.impl;

import com.demo.dto.ProductResponse;
import com.demo.entity.Product;
import com.demo.exception.BusinessException;
import com.demo.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 商品服務實作單元測試
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductServiceImpl productService;

    private Product product1;
    private Product product2;

    @BeforeEach
    void setUp() {
        product1 = new Product();
        product1.setProductId(1L);
        product1.setName("商品A");
        product1.setPrice(new BigDecimal("100.00"));

        product2 = new Product();
        product2.setProductId(2L);
        product2.setName("商品B");
        product2.setPrice(new BigDecimal("250.00"));
    }

    @Test
    @DisplayName("查詢所有商品 - 成功回傳清單")
    void findAllProducts_success() {
        // given
        when(productRepository.findAll()).thenReturn(List.of(product1, product2));

        // when
        List<ProductResponse> result = productService.findAllProducts();

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getProductId()).isEqualTo(1L);
        assertThat(result.get(0).getName()).isEqualTo("商品A");
        assertThat(result.get(0).getPrice()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(result.get(1).getProductId()).isEqualTo(2L);
        assertThat(result.get(1).getName()).isEqualTo("商品B");
    }

    @Test
    @DisplayName("查詢所有商品 - 回傳空清單")
    void findAllProducts_emptyList() {
        // given
        when(productRepository.findAll()).thenReturn(List.of());

        // when
        List<ProductResponse> result = productService.findAllProducts();

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("查詢所有商品 - 發生例外時拋出 BusinessException")
    void findAllProducts_throwBusinessException() {
        // given
        when(productRepository.findAll()).thenThrow(new RuntimeException("DB 連線失敗"));

        // when & then
        assertThatThrownBy(() -> productService.findAllProducts())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("查詢商品失敗");
    }
}