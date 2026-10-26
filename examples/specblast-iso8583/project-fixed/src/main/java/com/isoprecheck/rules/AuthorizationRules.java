package com.isoprecheck.rules;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 授權前檢查規則
 *
 * <p>此類別為純 Java 實作，不含任何 Spring 或 Lombok annotation，
 * 方便獨立做單元測試與驗證。
 *
 * <p>檢查順序：AC-01 → AC-02 → AC-03 → AC-04 → AC-05 → AC-06
 *
 * <p>PCI DSS 注意事項：此類別絕對不可 log 完整 PAN。
 */
public class AuthorizationRules {

    /** ISO 8583 回應碼：核准 */
    public static final String RC_APPROVED = "00";

    /** ISO 8583 回應碼：卡號無效 */
    public static final String RC_INVALID_CARD = "14";

    /** ISO 8583 回應碼：金額無效 */
    public static final String RC_INVALID_AMOUNT = "13";

    /** ISO 8583 回應碼：格式錯誤 */
    public static final String RC_FORMAT_ERROR = "30";

    /** ISO 8583 回應碼：餘額不足 */
    public static final String RC_INSUFFICIENT_FUNDS = "51";

    /** ISO 8583 回應碼：卡片過期 */
    public static final String RC_EXPIRED_CARD = "54";

    /**
     * 執行授權前檢查，依序套用所有規則。
     *
     * @param pan             主帳號（卡號）
     * @param expiry          到期日，格式 YYMM
     * @param amountMinor     交易金額（最小貨幣單位）
     * @param availableMinor  可用餘額（最小貨幣單位）
     * @param transactionDate 交易日期
     * @return ISO 8583 回應碼字串
     */
    public String check(String pan,
                        String expiry,
                        long amountMinor,
                        long availableMinor,
                        LocalDate transactionDate) {

        // AC-01：格式基本驗證
        if (!isFormatValid(pan, expiry)) {
            return RC_FORMAT_ERROR;
        }

        // AC-02：PAN 長度與 Luhn 驗證
        if (!isPanValid(pan)) {
            return RC_INVALID_CARD;
        }

        // AC-03：卡片是否過期
        if (isCardExpired(expiry, transactionDate)) {
            return RC_EXPIRED_CARD;
        }

        // AC-04：金額必須大於 0
        if (amountMinor <= 0) {
            return RC_INVALID_AMOUNT;
        }

        // AC-05：餘額是否足夠
        if (amountMinor > availableMinor) {
            return RC_INSUFFICIENT_FUNDS;
        }

        // AC-06：全部通過，核准
        return RC_APPROVED;
    }

    // -------------------------------------------------------------------------
    // 私有驗證方法
    // -------------------------------------------------------------------------

    /**
     * AC-01：驗證 pan 與 expiry 的基本格式。
     * expiry 必須剛好 4 個數字，月份（MM）必須是 01~12。
     */
    private boolean isFormatValid(String pan, String expiry) {
        if (pan == null || pan.isBlank()) {
            return false;
        }
        if (expiry == null || expiry.isBlank()) {
            return false;
        }
        // expiry 必須剛好 4 個 ASCII 數字
        if (!expiry.matches("\\d{4}")) {
            return false;
        }
        // 月份 MM 必須是 01~12
        int month = Integer.parseInt(expiry.substring(2, 4));
        return month >= 1 && month <= 12;
    }

    /**
     * AC-02：驗證 PAN 長度（13~19 位數字）並執行 Luhn 演算法。
     */
    private boolean isPanValid(String pan) {
        // 必須全為 ASCII 數字且長度 13~19
        if (!pan.matches("\\d{13,19}")) {
            return false;
        }
        return luhnCheck(pan);
    }

    /**
     * Luhn 演算法驗證。
     *
     * @param pan 卡號字串（純數字）
     * @return 驗證通過回傳 true
     */
    private boolean luhnCheck(String pan) {
        int sum = 0;
        boolean alternate = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int digit = pan.charAt(i) - '0';
            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    /**
     * AC-03：判斷卡片是否已過期。
     * 年份 YY 代表 20YY，有效期到到期月份最後一天（含）。
     * 只有交易日期「晚於」到期月份最後一天才算過期。
     *
     * @param expiry          到期日 YYMM
     * @param transactionDate 交易日期
     * @return 已過期回傳 true
     */
    private boolean isCardExpired(String expiry, LocalDate transactionDate) {
        int year  = 2000 + Integer.parseInt(expiry.substring(0, 2));
        int month = Integer.parseInt(expiry.substring(2, 4));

        YearMonth expiryYearMonth = YearMonth.of(year, month);
        LocalDate lastDayOfExpiryMonth = expiryYearMonth.atEndOfMonth();

        // 交易日期嚴格晚於到期月份最後一天才算過期
        return transactionDate.isAfter(lastDayOfExpiryMonth);
    }
}