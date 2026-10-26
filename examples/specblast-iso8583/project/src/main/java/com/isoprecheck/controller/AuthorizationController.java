package com.isoprecheck.controller;

import com.isoprecheck.common.ApiResponse;
import com.isoprecheck.dto.AuthorizationLogResponse;
import com.isoprecheck.dto.AuthorizationRequest;
import com.isoprecheck.dto.AuthorizationResponse;
import com.isoprecheck.service.AuthorizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 授權前檢查 Controller
 *
 * <p>提供 ISO 8583 授權請求的前置檢查 API。
 * 例外由 GlobalExceptionHandler 統一處理，不在此 catch。
 *
 * <p>PCI DSS 注意事項：不可在回應中揭露完整 PAN 或系統內部訊息。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/authorizations")
public class AuthorizationController {

    private final AuthorizationService authorizationService;

    /**
     * 執行授權前檢查
     *
     * <p>對應 ISO 8583 MTI 0100 授權請求的前置過濾。
     * 每筆請求都會寫入稽核紀錄。
     *
     * @param request 授權前檢查請求（含卡號、到期日、金額等）
     * @return ISO 8583 回應碼與說明
     */
    @PostMapping("/precheck")
    public ApiResponse<AuthorizationResponse> precheck(
            @Valid @RequestBody AuthorizationRequest request) {
        log.info("收到授權前檢查請求");
        AuthorizationResponse response = authorizationService.precheck(request);
        return ApiResponse.success(response);
    }

    /**
     * 查詢授權檢查紀錄
     *
     * <p>依建立時間由新到舊分頁回傳。
     *
     * @param page 頁碼（從 0 開始，預設 0）
     * @param size 每頁筆數（預設 20）
     * @return 授權紀錄分頁資料
     */
    @GetMapping("/precheck/logs")
    public ApiResponse<Page<AuthorizationLogResponse>> getLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<AuthorizationLogResponse> logs = authorizationService.getLogs(pageable);
        return ApiResponse.success(logs);
    }
}