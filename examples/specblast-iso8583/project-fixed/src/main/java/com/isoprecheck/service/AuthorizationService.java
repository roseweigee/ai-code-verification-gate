package com.isoprecheck.service;

import com.isoprecheck.dto.AuthorizationLogResponse;
import com.isoprecheck.dto.AuthorizationRequest;
import com.isoprecheck.dto.AuthorizationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 授權前檢查服務介面
 */
public interface AuthorizationService {

    /**
     * 執行授權前檢查並寫入稽核紀錄
     *
     * @param request 授權前檢查請求
     * @return 包含 ISO 8583 回應碼的回應
     */
    AuthorizationResponse precheck(AuthorizationRequest request);

    /**
     * 分頁查詢授權檢查紀錄（依建立時間由新到舊）
     *
     * @param pageable 分頁參數
     * @return 授權紀錄分頁資料
     */
    Page<AuthorizationLogResponse> getLogs(Pageable pageable);
}