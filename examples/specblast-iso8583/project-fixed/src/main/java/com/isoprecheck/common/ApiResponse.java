package com.isoprecheck.common;

import lombok.Getter;

/**
 * 統一 API 回傳格式
 *
 * @param <T> 回傳資料型別
 */
@Getter
public class ApiResponse<T> {

    /** 回傳代碼，0 表示成功 */
    private final int code;

    /** 回傳訊息 */
    private final String message;

    /** 回傳資料 */
    private final T data;

    private ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功回傳含資料
     *
     * @param data 回傳資料
     * @param <T>  資料型別
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    /**
     * 成功回傳不含資料
     *
     * @param <T> 資料型別
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(0, "success", null);
    }

    /**
     * 失敗回傳
     *
     * @param code    錯誤代碼
     * @param message 錯誤訊息
     * @param <T>     資料型別
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}