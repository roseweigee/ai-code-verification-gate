package com.card.exception;

/**
 * 業務邏輯例外
 */
public class BusinessException extends RuntimeException {

    /**
     * 建立業務例外
     *
     * @param message 錯誤訊息
     */
    public BusinessException(String message) {
        super(message);
    }
}