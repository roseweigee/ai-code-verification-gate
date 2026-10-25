package com.demo.exception;

/**
 * 業務例外
 */
public class BusinessException extends RuntimeException {

    /**
     * 建構子
     *
     * @param message 錯誤訊息
     */
    public BusinessException(String message) {
        super(message);
    }
}