package com.demo.exception;

import com.demo.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全域例外處理
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 處理業務例外
     *
     * @param ex 業務例外
     * @return 統一錯誤回應
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBusinessException(BusinessException ex) {
        log.warn("業務例外：{}", ex.getMessage());
        return ApiResponse.error(500, ex.getMessage());
    }

    /**
     * 處理所有未預期例外
     *
     * @param ex 例外
     * @return 統一錯誤回應
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleException(Exception ex) {
        log.error("系統例外：{}", ex.getMessage(), ex);
        return ApiResponse.error(500, "系統發生錯誤，請稍後再試");
    }
}