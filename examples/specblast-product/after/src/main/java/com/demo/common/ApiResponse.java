package com.demo.common;

import lombok.Getter;

/**
 * 統一回傳格式
 *
 * @param <T> 回傳資料型別
 */
@Getter
public class ApiResponse<T> {

    /** 狀態碼 */
    private final int code;

    /** 訊息 */
    private final String message;

    /** 回傳資料 */
    private final T data;

    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功含資料
     *
     * @param data 回傳資料
     * @param <T>  資料型別
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    /**
     * 成功不含資料
     *
     * @param <T> 資料型別
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(200, "success", null);
    }

    /**
     * 失敗
     *
     * @param code    錯誤碼
     * @param message 錯誤訊息
     * @param <T>     資料型別
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}