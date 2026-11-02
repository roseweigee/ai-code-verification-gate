package com.card.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDate;

/**
 * 授權預檢請求 DTO
 * <p>
 * 注意：欄位的存在性驗證由 {@code com.card.rules.AuthorizationRules} AC-01 處理，
 * 此處不使用 {@code @NotNull} 避免繞過業務規則的判斷順序。
 * PCI DSS NF-01：不得在 Log 中輸出 PAN。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuthorizationRequest {

    /**
     * 卡號（PAN）
     * PCI DSS：此欄位不得出現在任何 Log 或 console 輸出中
     */
    private String pan;

    /**
     * 到期日，格式 YYMM
     */
    private String expiry;

    /**
     * 交易金額（最小單位，例如分）
     */
    private Long amountMinor;

    /**
     * 可用餘額（最小單位）
     */
    private Long availableMinor;

    /**
     * 交易日期
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate transactionDate;
}