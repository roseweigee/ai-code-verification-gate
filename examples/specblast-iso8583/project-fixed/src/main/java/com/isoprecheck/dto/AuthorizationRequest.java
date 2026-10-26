package com.isoprecheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 授權前檢查請求 DTO
 */
@Getter
@Setter
@NoArgsConstructor
public class AuthorizationRequest {

    /**
     * 主帳號（卡號），對應 ISO 8583 DE 2
     * 完整 PAN 僅在記憶體中短暫存在，不寫入任何持久化儲存（PCI DSS 要求）
     */
    @NotBlank(message = "pan 不可為空")
    private String pan;

    /**
     * 卡片到期日，格式 YYMM，對應 ISO 8583 DE 14
     */
    @NotBlank(message = "expiry 不可為空")
    private String expiry;

    /**
     * 交易金額（最小貨幣單位），對應 ISO 8583 DE 4
     */
    @NotNull(message = "amountMinor 不可為空")
    private Long amountMinor;

    /**
     * 可用餘額（最小貨幣單位）
     */
    @NotNull(message = "availableMinor 不可為空")
    private Long availableMinor;

    /**
     * 交易日期，格式 yyyy-MM-dd，對應 ISO 8583 DE 13
     */
    @NotBlank(message = "transactionDate 不可為空")
    private String transactionDate;
}