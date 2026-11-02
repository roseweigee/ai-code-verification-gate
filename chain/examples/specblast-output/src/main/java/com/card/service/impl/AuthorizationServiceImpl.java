package com.card.service.impl;

import com.card.dto.AuthorizationRequest;
import com.card.dto.AuthorizationResponse;
import com.card.rules.AuthorizationRules;
import com.card.service.AuthorizationService;
import org.springframework.stereotype.Service;

/**
 * 授權預檢服務實作
 * <p>
 * 業務規則委派給 {@link AuthorizationRules}，此處不重複規則邏輯。
 * PCI DSS NF-01：不得記錄 PAN。
 */
@Service
public class AuthorizationServiceImpl implements AuthorizationService {

    private final AuthorizationRules authorizationRules;

    /**
     * 建構子注入
     *
     * @param authorizationRules 授權規則引擎
     */
    public AuthorizationServiceImpl(AuthorizationRules authorizationRules) {
        this.authorizationRules = authorizationRules;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 將請求欄位傳入 {@link AuthorizationRules#check} 取得結果碼後回傳。
     * 缺少的欄位以 {@code null} 傳入。
     */
    @Override
    public AuthorizationResponse precheck(AuthorizationRequest request) {
        // PCI DSS：此處不記錄任何包含 PAN 的資訊
        String resultCode = authorizationRules.check(
                request.getPan(),
                request.getExpiry(),
                request.getAmountMinor() != null ? request.getAmountMinor() : 0L,
                request.getAvailableMinor() != null ? request.getAvailableMinor() : 0L,
                request.getTransactionDate()
        );
        return new AuthorizationResponse(resultCode);
    }
}