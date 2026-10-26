-- =====================================================
-- 授權檢查紀錄資料表（給 SIT/UAT/PROD MySQL 使用）
-- =====================================================
CREATE TABLE IF NOT EXISTS authorization_logs (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自動遞增主鍵',
    masked_pan    VARCHAR(19)  NOT NULL                COMMENT '遮罩後的卡號：前6碼+星號+後4碼',
    amount_minor  BIGINT       NOT NULL                COMMENT '交易金額（最小貨幣單位）',
    response_code CHAR(2)      NOT NULL                COMMENT 'ISO 8583 回應碼',
    created_at    DATETIME     NOT NULL                COMMENT '建立時間',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='授權前檢查紀錄';