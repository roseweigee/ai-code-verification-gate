package com.isoprecheck.exception;

/**
 * 業務邏輯例外
 * 當業務規則不符合時拋出此例外
 */
public class BusinessException extends RuntimeException {

    /**
     * 建構業務例外
     *
     * @param message 錯誤訊息
     */
    public BusinessException(String message) {
        super(message);
    }
}