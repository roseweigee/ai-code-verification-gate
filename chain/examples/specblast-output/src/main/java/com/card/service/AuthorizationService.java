package com.card.service;

import com.card.dto.AuthorizationRequest;
import com.card.dto.AuthorizationResponse;

/**
 * 授權預檢服務介面
 */
public interface AuthorizationService {

    /**
     * 執行授權預檢，依序評估 AC-01 至 AC-06 規則
     *
     * @param request 授權請求
     * @return 授權預檢回應，含 ISO 8583 結果碼
     */
    AuthorizationResponse precheck(AuthorizationRequest request);
}