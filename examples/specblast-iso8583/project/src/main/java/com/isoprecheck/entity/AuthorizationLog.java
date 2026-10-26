package com.isoprecheck.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 授權前檢查紀錄 Entity
 * 對應資料表 authorization_logs
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "authorization_logs")
public class AuthorizationLog {

    /** 自動遞增主鍵 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * 遮罩後的卡號（前6碼 + 星號 + 後4碼）
     * 不儲存完整 PAN（PCI DSS 要求）
     */
    @Column(name = "masked_pan", nullable = false, length = 19)
    private String maskedPan;

    /** 交易金額（最小貨幣單位，例如分） */
    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    /** ISO 8583 回應碼 */
    @Column(name = "response_code", nullable = false, length = 2)
    private String responseCode;

    /** 建立時間 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}