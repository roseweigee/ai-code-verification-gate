-- 商品資料表（給 SIT/UAT/PROD MySQL 用）
CREATE TABLE IF NOT EXISTS products (
    product_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT '商品主鍵，自動遞增',
    name       VARCHAR(100) NOT NULL               COMMENT '商品名稱',
    price      DECIMAL(10, 2) NOT NULL             COMMENT '商品價格',
    PRIMARY KEY (product_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '商品資料表';