package com.card.controller;

import com.card.dto.AuthorizationResponse;
import com.card.service.AuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthorizationController 整合測試
 * <p>
 * PCI DSS NF-01：測試中不得輸出 PAN 到 Log 或 console。
 */
@WebMvcTest(AuthorizationController.class)
@DisplayName("AuthorizationController 測試")
class AuthorizationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthorizationService authorizationService;

    @Test
    @DisplayName("POST /authorizations/precheck → 回傳 00")
    void precheck_validRequest_returns00() throws Exception {
        when(authorizationService.precheck(any())).thenReturn(new AuthorizationResponse("00"));

        String body = """
                {
                  "pan": "4111111111111111",
                  "expiry": "2610",
                  "amountMinor": 1000,
                  "availableMinor": 5000,
                  "transactionDate": "2026-10-15"
                }
                """;

        mockMvc.perform(post("/authorizations/precheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resultCode").value("00"));
    }

    @Test
    @DisplayName("POST /authorizations/precheck → 回傳 54（過期）")
    void precheck_expiredCard_returns54() throws Exception {
        when(authorizationService.precheck(any())).thenReturn(new AuthorizationResponse("54"));

        String body = """
                {
                  "pan": "4111111111111111",
                  "expiry": "2610",
                  "amountMinor": 1000,
                  "availableMinor": 5000,
                  "transactionDate": "2026-11-01"
                }
                """;

        mockMvc.perform(post("/authorizations/precheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultCode").value("54"));
    }

    @Test
    @DisplayName("POST /authorizations/precheck → 回傳 30（格式錯誤）")
    void precheck_formatError_returns30() throws Exception {
        when(authorizationService.precheck(any())).thenReturn(new AuthorizationResponse("30"));

        String body = """
                {
                  "pan": "4111111111111111",
                  "expiry": "2613",
                  "amountMinor": 1000,
                  "availableMinor": 5000,
                  "transactionDate": "2026-10-15"
                }
                """;

        mockMvc.perform(post("/authorizations/precheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultCode").value("30"));
    }
}