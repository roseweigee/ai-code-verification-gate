package com.isoprecheck.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AuthorizationRules 單元測試
 *
 * <p>測試所有 AC-01 ~ AC-06 規則的正常與邊界情境。
 * 此測試不需要 Spring Context，純 Java 執行。
 */
@DisplayName("AuthorizationRules 授權規則單元測試")
class AuthorizationRulesTest {

    private AuthorizationRules rules;

    /** 有效的測試卡號（通過 Luhn 驗證） */
    private static final String VALID_PAN = "4111111111111111";

    /** 有效的到期日（遠未到期） */
    private static final String VALID_EXPIRY = "2612";

    /** 標準交易日期 */
    private static final LocalDate TX_DATE = LocalDate.of(2024, 6, 15);

    /** 標準可用餘額（1000元 = 100000分） */
    private static final long AVAILABLE = 100_000L;

    @BeforeEach
    void setUp() {
        rules = new AuthorizationRules();
    }

    // =========================================================================
    // AC-06：全部通過，應核准
    // =========================================================================

    @Nested
    @DisplayName("AC-06 核准情境")
    class Ac06ApprovedTests {

        @Test
        @DisplayName("所有欄位正確，應回傳 00 核准")
        void shouldReturnApprovedWhenAllValid() {
            String result = rules.check(VALID_PAN, VALID_EXPIRY, 50_000L, AVAILABLE, TX_DATE);
            assertEquals("00", result);
        }

        @Test
        @DisplayName("金額等於可用餘額，應核准（AC-05 邊界）")
        void shouldApproveWhenAmountEqualsAvailable() {
            String result = rules.check(VALID_PAN, VALID_EXPIRY, AVAILABLE, AVAILABLE, TX_DATE);
            assertEquals("00", result);
        }

        @Test
        @DisplayName("交易日期等於到期月最後一天，應核准（AC-03 邊界）")
        void shouldApproveWhenTxDateIsLastDayOfExpiryMonth() {
            // 到期 2412 = 2024/12，最後一天 2024-12-31
            LocalDate lastDay = LocalDate.of(2024, 12, 31);
            String result = rules.check(VALID_PAN, "2412", 1_000L, AVAILABLE, lastDay);
            assertEquals("00", result);
        }
    }

    // =========================================================================
    // AC-01：格式錯誤
    // =========================================================================

    @Nested
    @DisplayName("AC-01 格式錯誤情境")
    class Ac01FormatErrorTests {

        @Test
        @DisplayName("expiry 不是 4 位數字，應回傳 30")
        void shouldReturnFormatErrorWhenExpiryInvalidLength() {
            String result = rules.check(VALID_PAN, "261", 1_000L, AVAILABLE, TX_DATE);
            assertEquals("30", result);
        }

        @Test
        @DisplayName("expiry 月份為 00，應回傳 30")
        void shouldReturnFormatErrorWhenExpiryMonthIsZero() {
            String result = rules.check(VALID_PAN, "2600", 1_000L, AVAILABLE, TX_DATE);
            assertEquals("30", result);
        }

        @Test
        @DisplayName("expiry 月份為 13，應回傳 30")
        void shouldReturnFormatErrorWhenExpiryMonthIs13() {
            String result = rules.check(VALID_PAN, "2613", 1_000L, AVAILABLE, TX_DATE);
            assertEquals("30", result);
        }

        @Test
        @DisplayName("expiry 含非數字字元，應回傳 30")
        void shouldReturnFormatErrorWhenExpiryContainsNonDigit() {
            String result = rules.check(VALID_PAN, "26AB", 1_000L, AVAILABLE, TX_DATE);
            assertEquals("30", result);
        }

        @Test
        @DisplayName("pan 為空字串，應回傳 30")
        void shouldReturnFormatErrorWhenPanIsBlank() {
            String result = rules.check("", VALID_EXPIRY, 1_000L, AVAILABLE, TX_DATE);
            assertEquals("30", result);
        }

        @Test
        @DisplayName("expiry 為空字串，應回傳 30")
        void shouldReturnFormatErrorWhenExpiryIsBlank() {
            String result = rules.check(VALID_PAN, "", 1_000L, AVAILABLE, TX_DATE);
            assertEquals("30", result);
        }
    }

    // =========================================================================
    // AC-02：卡號無效
    // =========================================================================

    @Nested
    @DisplayName("AC-02 卡號無效情境")
    class Ac02InvalidCardTests {

        @Test
        @DisplayName("PAN 長度不足（12位），應回傳 14")
        void shouldReturnInvalidCardWhenPanTooShort() {
            String result = rules.check("411111111111", VALID_EXPIRY, 1_000L, AVAILABLE, TX_DATE);
            assertEquals("14", result);
        }

        @Test
        @DisplayName("PAN 長度過長（20位），應回傳 14")
        void shouldReturnInvalidCardWhenPanTooLong() {
            String result = rules.check("41111111111111111119", VALID_EXPIRY, 1_000L, AVAILABLE, TX_DATE);
            assertEquals("14", result);
        }

        @Test
        @DisplayName("PAN 含非數字字元，應回傳 14")
        void shouldReturnInvalidCardWhenPanContainsNonDigit() {
            String result = rules.check("4111111111111A11", VALID_EXPIRY, 1_000L, AVAILABLE, TX_DATE);
            assertEquals("14", result);
        }

        @Test
        @DisplayName("PAN 未通過 Luhn 驗證，應回傳 14")
        void shouldReturnInvalidCardWhenLuhnFails() {
            // 4111111111111112 最後一位改為 2，Luhn 應失敗
            String result = rules.check("4111111111111112", VALID_EXPIRY, 1_000L, AVAILABLE, TX_DATE);
            assertEquals("14", result);
        }
    }

    // =========================================================================
    // AC-03：卡片過期
    // =========================================================================

    @Nested
    @DisplayName("AC-03 卡片過期情境")
    class Ac03ExpiredCardTests {

        @Test
        @DisplayName("交易日期晚於到期月最後一天，應回傳 54")
        void shouldReturnExpiredCardWhenTxDateAfterExpiryLastDay() {
            // 到期 2312 = 2023/12，最後一天 2023-12-31，交易日 2024-01-01
            LocalDate txDate = LocalDate.of(2024, 1, 1);
            String result = rules.check(VALID_PAN, "2312", 1_000L, AVAILABLE, txDate);
            assertEquals("54", result);
        }

        @Test
        @DisplayName("交易日期在到期月中，應核准（非過期）")
        void shouldApproveWhenTxDateWithinExpiryMonth() {
            // 到期 2406 = 2024/06，交易日 2024-06-15（月中）
            String result = rules.check(VALID_PAN, "2406", 1_000L, AVAILABLE, TX_DATE);
            assertEquals("00", result);
        }
    }

    // =========================================================================
    // AC-04：金額無效
    // =========================================================================

    @Nested
    @DisplayName("AC-04 金額無效情境")
    class Ac04InvalidAmountTests {

        @Test
        @DisplayName("金額為 0，應回傳 13")
        void shouldReturnInvalidAmountWhenZero() {
            String result = rules.check(VALID_PAN, VALID_EXPIRY, 0L, AVAILABLE, TX_DATE);
            assertEquals("13", result);
        }

        @Test
        @DisplayName("金額為負數，應回傳 13")
        void shouldReturnInvalidAmountWhenNegative() {
            String result = rules.check(VALID_PAN, VALID_EXPIRY, -100L, AVAILABLE, TX_DATE);
            assertEquals("13", result);
        }
    }

    // =========================================================================
    // AC-05：餘額不足
    // =========================================================================

    @Nested
    @DisplayName("AC-05 餘額不足情境")
    class Ac05InsufficientFundsTests {

        @Test
        @DisplayName("金額超過可用餘額，應回傳 51")
        void shouldReturnInsufficientFundsWhenAmountExceedsAvailable() {
            String result = rules.check(VALID_PAN, VALID_EXPIRY, AVAILABLE + 1L, AVAILABLE, TX_DATE);
            assertEquals("51", result);
        }

        @Test
        @DisplayName("金額大幅超過可用餘額，應回傳 51")
        void shouldReturnInsufficientFundsWhenAmountFarExceedsAvailable() {
            String result = rules.check(VALID_PAN, VALID_EXPIRY, 999_999_999L, 1L, TX_DATE);
            assertEquals("51", result);
        }
    }

    // =========================================================================
    // 規則優先順序驗證
    // =========================================================================

    @Nested
    @DisplayName("規則優先順序驗證")
    class RulePriorityTests {

        @Test
        @DisplayName("AC-01 優先於 AC-02：格式錯誤應先回傳 30")
        void shouldReturnFormatErrorBeforeInvalidCard() {
            // expiry 格式錯誤，pan 也不合法，應先回傳 30
            String result = rules.check("1234", "ABCD", 1_000L, AVAILABLE, TX_DATE);
            assertEquals("30", result);
        }

        @Test
        @DisplayName("AC-02 優先於 AC-03：卡號無效應先回傳 14")
        void shouldReturnInvalidCardBeforeExpired() {
            // PAN Luhn 失敗，且假設卡片過期，應先回傳 14
            LocalDate pastDate = LocalDate.of(2025, 1, 1);
            String result = rules.check("4111111111111112", "2012", 1_000L, AVAILABLE, pastDate);
            assertEquals("14", result);
        }
    }
}