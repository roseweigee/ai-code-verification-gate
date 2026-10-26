package com.isoprecheck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 授權前檢查回應 DTO
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorizationResponse {

    /** ISO 8583 回應碼（例如 "00" 表示核准） */
    private String responseCode;

    /** 回應碼的說明文字 */
    private String message;
}