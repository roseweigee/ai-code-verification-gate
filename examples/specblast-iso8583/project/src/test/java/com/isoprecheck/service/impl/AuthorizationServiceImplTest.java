package com.isoprecheck.service.impl;

import com.isoprecheck.dto.AuthorizationRequest;
import com.isoprecheck.dto.AuthorizationResponse;
import com.isoprecheck.entity.AuthorizationLog;
import com.isoprecheck.exception.BusinessException;
import com.isoprecheck.repository.AuthorizationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthorizationServiceImpl 單元測試
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorizationServiceImpl 服務層單元測試")
class AuthorizationServiceImplTest {

    @Mock
    private AuthorizationLogRepository authorizationLogRepository;

    @InjectMocks
    private AuthorizationServiceImpl authorizationService;

    private AuthorizationRequest validRequest;

    @BeforeEach
    void setUp() {
        validRequest = new AuthorizationRequest();
        validRequest.setPan("4111111111111111");
        validRequest.setExpiry("2612");
        validRequest.setAmountMinor(50_000L);
        validRequest.setAvailableMinor(100_000L);
        validRequest.setTransactionDate("2024-06-15");
    }

    @Test
    @DisplayName("正常授權請求應回傳 00 核准，並儲存遮罩 PAN")
    void shouldReturnApprovedAndSaveMaskedPan() {
        when(authorizationLogRepository.save(any())).thenReturn(new AuthorizationLog());

        AuthorizationResponse response = authorizationService.precheck(validRequest);

        assertEquals("00", response.getResponseCode());
        assertEquals("核准", response.getMessage());

        // 驗證儲存的 PAN 已遮罩，不含完整卡號
        ArgumentCaptor<AuthorizationLog> captor = ArgumentCaptor.forClass(AuthorizationLog.class);
        verify(authorizationLogRepository).save(captor.capture());

        String maskedPan = captor.getValue().getMaskedPan();
        // 確認遮罩格式正確：前6碼 + 星號 + 後4碼
        assertTrue(maskedPan.startsWith("411111"), "應以原始前6碼開頭");
        assertTrue(maskedPan.endsWith("1111"), "應以原始後4碼結尾");
        assertTrue(maskedPan.contains("*"), "遮罩 PAN 應包含星號");
        // 確認不含完整 PAN
        assertTrue(!maskedPan.equals("4111111111111111"), "遮罩 PAN 不可與原始 PAN 相同");
    }

    @Test
    @DisplayName("transactionDate 格式錯誤應拋出 BusinessException")
    void shouldThrowBusinessExceptionWhenInvalidDateFormat() {
        validRequest.setTransactionDate("20240615");

        assertThrows(BusinessException.class,
                () -> authorizationService.precheck(validRequest));
    }

    @Test
    @DisplayName("餘額不足應回傳 51")
    void shouldReturnInsufficientFundsWhenAmountExceedsAvailable() {
        validRequest.setAmountMinor(200_000L);
        validRequest.setAvailableMinor(100_000L);

        when(authorizationLogRepository.save(any())).thenReturn(new AuthorizationLog());

        AuthorizationResponse response = authorizationService.precheck(validRequest);

        assertEquals("51", response.getResponseCode());
        assertEquals("餘額不足", response.getMessage());
    }
}