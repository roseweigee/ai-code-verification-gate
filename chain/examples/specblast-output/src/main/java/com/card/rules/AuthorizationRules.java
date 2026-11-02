package com.card.rules;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * 卡片授權規則引擎。
 * <p>
 * 此類別不得包含任何 Spring、Lombok、資料庫或 Log 相關程式碼。
 * 僅使用 java.time、java.util、java.util.regex。
 * <p>
 * 規則按照 AC-01 → AC-06 順序評估，第一個命中的規則決定結果。
 * PCI DSS：方法內部不得將 PAN 輸出到任何輸出流。
 */
public class AuthorizationRules {

    /** YYMM 格式，月份 01-12 */
    private static final Pattern EXPIRY_PATTERN = Pattern.compile("^[0-9]{2}(0[1-9]|1[0-2])$");

    /** PAN 格式：13-19 位數字（僅 ASCII digit）*/
    private static final Pattern PAN_PATTERN = Pattern.compile("^[0-9]{13,19}$");

    /**
     * 依序評估所有規則，回傳第一個命中規則的 ISO 8583 DE 39 結果碼。
     *
     * @param pan             卡號；缺少時傳入 {@code null}
     * @param expiry          到期日 YYMM；缺少時傳入 {@code null}
     * @param amountMinor     交易金額（最小單位）
     * @param availableMinor  可用餘額（最小單位）
     * @param transactionDate 交易日期；缺少時傳入 {@code null}
     * @return ISO 8583 回應碼字串，例如 {@code "00"} 或 {@code "54"}
     */
    public String check(String pan,
                        String expiry,
                        long amountMinor,
                        long availableMinor,
                        LocalDate transactionDate) {

        // AC-01：必填欄位檢核 & expiry 格式
        if (pan == null || pan.isEmpty()
                || expiry == null || expiry.isEmpty()
                || transactionDate == null
                || !EXPIRY_PATTERN.matcher(expiry).matches()) {
            return "30";
        }

        // AC-02：PAN 格式 & Luhn 檢核
        if (!PAN_PATTERN.matcher(pan).matches() || !passesLuhn(pan)) {
            return "14";
        }

        // AC-03：卡片是否過期（以 expiry 月份最後一天為基準）
        LocalDate lastDay = lastDayOfExpiry(expiry);
        if (transactionDate.isAfter(lastDay)) {
            return "54";
        }

        // AC-04：金額必須大於 0
        if (amountMinor <= 0) {
            return "13";
        }

        // AC-05：金額不得超過可用餘額（等於允許）
        if (amountMinor > availableMinor) {
            return "51";
        }

        // AC-06：核准
        return "00";
    }

    // -------------------------------------------------------------------------
    // Private helpers — 不得記錄 PAN
    // -------------------------------------------------------------------------

    /**
     * Luhn 演算法驗證。
     *
     * @param digits 純數字字串（呼叫前已確認格式正確）
     * @return {@code true} 代表通過 Luhn 檢核
     */
    private boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    /**
     * 根據 YYMM 字串計算該月份最後一天。
     * 例如 "2610" → 2026 年 10 月 31 日。
     *
     * @param expiry YYMM 格式字串（已通過格式驗證）
     * @return 該月份最後一天的 {@link LocalDate}
     */
    private LocalDate lastDayOfExpiry(String expiry) {
        // YYMM → 解析年份（20YY）與月份
        int yy = Integer.parseInt(expiry.substring(0, 2));
        int mm = Integer.parseInt(expiry.substring(2, 4));
        // 假設 20xx 世紀（符合現行信用卡有效期間慣例）
        int year = 2000 + yy;
        YearMonth ym = YearMonth.of(year, mm);
        return ym.atEndOfMonth();
    }
}