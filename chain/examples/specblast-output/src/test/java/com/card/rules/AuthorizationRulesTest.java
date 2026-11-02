package com.card.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthorizationRules 單元測試
 * <p>
 * 涵蓋規格中所有驗收測試案例（AC-01 至 AC-06）。
 * PCI DSS NF-01：測試中不得將 PAN 輸出至 console 或 Log。
 */
@DisplayName("AuthorizationRules 授權規則引擎測試")
class AuthorizationRulesTest {

    private AuthorizationRules rules;

    @BeforeEach
    void setUp() {
        rules = new AuthorizationRules();
    }

    // =========================================================================
    // AC-01：必填欄位與 expiry 格式
    // =========================================================================

    @Test
    @DisplayName("AC-01: expiry 月份為 13 → 30")
    void ac01_invalidExpiryMonth_returns30() {
        // {"amountMinor":1000,"availableMinor":5000,"expiry":"2613","pan":"4111111111111111","transactionDate":"2026-10-15"} → 30
        String result = rules.check(
                "4111111111111111", "2613",
                1000L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isEqualTo("30");
    }

    @Test
    @DisplayName("AC-01: transactionDate 為 null（空字串模擬）→ 30")
    void ac01_missingTransactionDate_returns30() {
        // {"amountMinor":1000,"availableMinor":5000,"expiry":"2610","pan":"4111111111111111","transactionDate":""} → 30
        String result = rules.check(
                "4111111111111111", "2610",
                1000L, 5000L,
                null);
        assertThat(result).isEqualTo("30");
    }

    @Test
    @DisplayName("AC-01: pan 為 null → 30")
    void ac01_missingPan_returns30() {
        String result = rules.check(
                null, "2610",
                1000L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isEqualTo("30");
    }

    @Test
    @DisplayName("AC-01: expiry 為 null → 30")
    void ac01_missingExpiry_returns30() {
        String result = rules.check(
                "4111111111111111", null,
                1000L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isEqualTo("30");
    }

    @Test
    @DisplayName("AC-01: expiry 月份 00 無效 → 30")
    void ac01_expiryMonthZero_returns30() {
        String result = rules.check(
                "4111111111111111", "2600",
                1000L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isEqualTo("30");
    }

    // =========================================================================
    // AC-02：PAN 格式與 Luhn
    // =========================================================================

    @Test
    @DisplayName("AC-02: Luhn 檢核失敗（末位改 2）→ 14")
    void ac02_luhnFail_returns14() {
        // {"amountMinor":1000,"availableMinor":5000,"expiry":"2610","pan":"4111111111111112","transactionDate":"2026-10-15"} → 14
        String result = rules.check(
                "4111111111111112", "2610",
                1000L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isEqualTo("14");
    }

    @Test
    @DisplayName("AC-02: 含全形數字（非 ASCII digit）→ 14")
    void ac02_fullWidthDigits_returns14() {
        // {"amountMinor":1000,"availableMinor":5000,"expiry":"2610","pan":"４111111111111111","transactionDate":"2026-10-15"} → 14
        // 全形 ４ 不符合 ^[0-9]{13,19}$
        String result = rules.check(
                "４111111111111111", "2610",
                1000L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isEqualTo("14");
    }

    @Test
    @DisplayName("AC-02: PAN 少於 13 位 → 14")
    void ac02_panTooShort_returns14() {
        String result = rules.check(
                "411111111111", "2610",
                1000L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isEqualTo("14");
    }

    // =========================================================================
    // AC-03：卡片過期（以月份最後一天為基準）
    // =========================================================================

    @Test
    @DisplayName("AC-03: 交易日 = 到期月最後一天 → 00（核准）")
    void ac03_transactionOnLastDay_returns00() {
        // {"amountMinor":1000,"availableMinor":5000,"expiry":"2610","pan":"4111111111111111","transactionDate":"2026-10-31"} → 00
        String result = rules.check(
                "4111111111111111", "2610",
                1000L, 5000L,
                LocalDate.of(2026, 10, 31));
        assertThat(result).isEqualTo("00");
    }

    @Test
    @DisplayName("AC-03: 交易日 = 到期月最後一天 + 1 → 54（過期）")
    void ac03_transactionOneDayAfterLastDay_returns54() {
        // {"amountMinor":1000,"availableMinor":5000,"expiry":"2610","pan":"4111111111111111","transactionDate":"2026-11-01"} → 54
        String result = rules.check(
                "4111111111111111", "2610",
                1000L, 5000L,
                LocalDate.of(2026, 11, 1));
        assertThat(result).isEqualTo("54");
    }

    // =========================================================================
    // AC-04：金額必須大於 0
    // =========================================================================

    @Test
    @DisplayName("AC-04: amountMinor = 0 → 13")
    void ac04_amountZero_returns13() {
        // {"amountMinor":0,"availableMinor":5000,"expiry":"2610","pan":"4111111111111111","transactionDate":"2026-10-15"} → 13
        String result = rules.check(
                "4111111111111111", "2610",
                0L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isEqualTo("13");
    }

    @Test
    @DisplayName("AC-04: amountMinor 為負數 → 13")
    void ac04_negativeAmount_returns13() {
        String result = rules.check(
                "4111111111111111", "2610",
                -1L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isEqualTo("13");
    }

    // =========================================================================
    // AC-05：餘額不足
    // =========================================================================

    @Test
    @DisplayName("AC-05: amountMinor = availableMinor → 00（等於允許）")
    void ac05_amountEqualsAvailable_returns00() {
        // {"amountMinor":5000,"availableMinor":5000,"expiry":"2610","pan":"4111111111111111","transactionDate":"2026-10-15"} → 00
        String result = rules.check(
                "4111111111111111", "2610",
                5000L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isEqualTo("00");
    }

    @Test
    @DisplayName("AC-05: amountMinor > availableMinor → 51")
    void ac05_amountExceedsAvailable_returns51() {
        // {"amountMinor":5001,"availableMinor":5000,"expiry":"2610","pan":"4111111111111111","transactionDate":"2026-10-15"} → 51
        String result = rules.check(
                "4111111111111111", "2610",
                5001L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isEqualTo("51");
    }

    // =========================================================================
    // AC-06：核准
    // =========================================================================

    @Test
    @DisplayName("AC-06: 所有條件正常 → 00")
    void ac06_allValid_returns00() {
        // {"amountMinor":1000,"availableMinor":5000,"expiry":"2610","pan":"4111111111111111","transactionDate":"2026-10-15"} → 00
        String result = rules.check(
                "4111111111111111", "2610",
                1000L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isEqualTo("00");
    }

    // =========================================================================
    // Luhn 邊界測試
    // =========================================================================

    @Test
    @DisplayName("Luhn: 已知合法 PAN 4111111111111111 → 通過")
    void luhn_knownValidPan_passes() {
        // 透過 AC-06 驗證 Luhn 通過
        String result = rules.check(
                "4111111111111111", "2610",
                1000L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isNotEqualTo("14");
    }

    @Test
    @DisplayName("AC-01 優先於 AC-02：expiry 無效時不評估 Luhn")
    void ac01_takePriorityOverAc02() {
        // expiry 格式錯誤，應回傳 30 而非 14
        String result = rules.check(
                "4111111111111112", "2613",
                1000L, 5000L,
                LocalDate.of(2026, 10, 15));
        assertThat(result).isEqualTo("30");
    }
}