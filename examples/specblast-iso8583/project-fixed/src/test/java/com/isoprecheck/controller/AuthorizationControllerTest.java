package com.isoprecheck.controller;

import com.isoprecheck.dto.AuthorizationRequest;
import com.isoprecheck.dto.AuthorizationResponse;
import com.isoprecheck.exception.GlobalExceptionHandler;
import com.isoprecheck.service.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthorizationController 單元測試（MockMvc）
 *
 * <p>補上品質關卡 T-01 指出缺少的 controller 測試：
 * 正常路徑回傳回應碼；缺少交易日期時回 400，且不呼叫服務層。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorizationController API 測試")
class AuthorizationControllerTest {

    @Mock
    private AuthorizationService authorizationService;

    @InjectMocks
    private AuthorizationController authorizationController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(authorizationController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("正常請求：回傳 200 與回應碼 00")
    void precheck_validRequest_returnsResponseCode() throws Exception {
        AuthorizationResponse response = new AuthorizationResponse();
        response.setResponseCode("00");
        response.setMessage("Approved");
        when(authorizationService.precheck(any(AuthorizationRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/authorizations/precheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pan\":\"4111111111111111\",\"expiry\":\"2812\",\"amountMinor\":120000,"
                                + "\"availableMinor\":5000000,\"transactionDate\":\"2026-10-27\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responseCode").value("00"));
    }

    @Test
    @DisplayName("缺少交易日期：回傳 400，不呼叫服務層")
    void precheck_missingDate_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/authorizations/precheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pan\":\"4111111111111111\",\"expiry\":\"2812\",\"amountMinor\":120000,"
                                + "\"availableMinor\":5000000}"))
                .andExpect(status().isBadRequest());
        verify(authorizationService, never()).precheck(any());
    }
}
