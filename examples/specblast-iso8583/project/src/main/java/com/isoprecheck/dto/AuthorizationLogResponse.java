package com.isoprecheck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 授權檢查紀錄回應 DTO
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorizationLogResponse {

    /** 紀錄 ID */
    private Long id;

    /** 遮罩後的卡號 */
    private String maskedPan;

    /** 交易金額（最小貨幣單位） */
    private Long amountMinor;

    /** ISO 8583 回應碼 */
    private String responseCode;

    /** 建立時間 */
    private LocalDateTime createdAt;
}