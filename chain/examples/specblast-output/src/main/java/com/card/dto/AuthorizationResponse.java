package com.card.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 授權預檢回應 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuthorizationResponse {

    /**
     * ISO 8583 DE 39 結果碼
     * 可能值：00（核准）、13（金額無效）、14（卡號無效）、30（格式錯誤）、51（餘額不足）、54（卡片過期）
     */
    private String resultCode;
}