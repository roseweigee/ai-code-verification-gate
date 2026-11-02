package com.card.controller;

import com.card.common.ApiResponse;
import com.card.dto.AuthorizationRequest;
import com.card.dto.AuthorizationResponse;
import com.card.service.AuthorizationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 卡片授權預檢 Controller
 * <p>
 * PCI DSS NF-01：此 Controller 不得記錄任何含有 PAN 的資訊。
 */
@RestController
@RequestMapping("/authorizations")
public class AuthorizationController {

    private final AuthorizationService authorizationService;

    /**
     * 建構子注入
     *
     * @param authorizationService 授權預檢服務
     */
    public AuthorizationController(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    /**
     * 授權預檢端點
     * <p>
     * POST /authorizations/precheck
     * 接收授權請求，依序套用 AC-01 至 AC-06 規則，回傳第一個命中規則的 ISO 8583 結果碼。
     *
     * @param request 授權預檢請求
     * @return 包含結果碼的統一回應
     */
    @PostMapping(value = "/precheck", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<AuthorizationResponse> precheck(@RequestBody AuthorizationRequest request) {
        AuthorizationResponse response = authorizationService.precheck(request);
        return ApiResponse.success(response);
    }
}