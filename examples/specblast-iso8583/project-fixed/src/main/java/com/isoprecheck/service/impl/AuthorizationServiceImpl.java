package com.isoprecheck.service.impl;

import com.isoprecheck.dto.AuthorizationLogResponse;
import com.isoprecheck.dto.AuthorizationRequest;
import com.isoprecheck.dto.AuthorizationResponse;
import com.isoprecheck.entity.AuthorizationLog;
import com.isoprecheck.exception.BusinessException;
import com.isoprecheck.repository.AuthorizationLogRepository;
import com.isoprecheck.rules.AuthorizationRules;
import com.isoprecheck.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 授權前檢查服務實作
 *
 * <p>PCI DSS 注意事項：
 * <ul>
 *   <li>完整 PAN 絕對不可寫入 log 或資料庫</li>
 *   <li>所有錯誤回應不可包含系統內部訊息</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizationServiceImpl implements AuthorizationService {

    private final AuthorizationLogRepository authorizationLogRepository;

    /** 授權規則引擎（純 Java 類別，不含 Spring 注入） */
    private final AuthorizationRules authorizationRules = new AuthorizationRules();

    /**
     * 回應碼對應說明文字
     */
    private static final Map<String, String> RESPONSE_CODE_MESSAGES = Map.of(
            "00", "核准",
            "13", "金額無效",
            "14", "卡號無效",
            "30", "格式錯誤",
            "51", "餘額不足",
            "54", "卡片過期"
    );

    /**
     * {@inheritDoc}
     *
     * <p>執行順序：
     * <ol>
     *   <li>解析交易日期</li>
     *   <li>呼叫 AuthorizationRules 取得回應碼</li>
     *   <li>寫入稽核紀錄（含遮罩 PAN）</li>
     *   <li>回傳結果</li>
     * </ol>
     */
    @Override
    @Transactional
    public AuthorizationResponse precheck(AuthorizationRequest request) {
        // 解析交易日期
        LocalDate transactionDate = parseTransactionDate(request.getTransactionDate());

        // 執行授權規則（不可在此 log PAN）
        String responseCode = authorizationRules.check(
                request.getPan(),
                request.getExpiry(),
                request.getAmountMinor(),
                request.getAvailableMinor(),
                transactionDate
        );

        // 遮罩 PAN 後寫入稽核紀錄（PCI DSS 要求）
        String maskedPan = maskPan(request.getPan());
        saveAuthorizationLog(maskedPan, request.getAmountMinor(), responseCode);

        log.info("授權前檢查完成 maskedPan={} responseCode={}", maskedPan, responseCode);

        String message = RESPONSE_CODE_MESSAGES.getOrDefault(responseCode, "未知回應碼");
        return AuthorizationResponse.builder()
                .responseCode(responseCode)
                .message(message)
                .build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public Page<AuthorizationLogResponse> getLogs(Pageable pageable) {
        return authorizationLogRepository
                .findAllByOrderByCreatedAtDesc(pageable)
                .map(this::toLogResponse);
    }

    // -------------------------------------------------------------------------
    // 私有方法
    // -------------------------------------------------------------------------

    /**
     * 解析交易日期字串，格式必須為 yyyy-MM-dd。
     *
     * @param dateStr 日期字串
     * @return LocalDate
     * @throws BusinessException 格式不正確時拋出
     */
    private LocalDate parseTransactionDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr);
        } catch (DateTimeParseException e) {
            throw new BusinessException("transactionDate 格式錯誤，必須為 yyyy-MM-dd");
        }
    }

    /**
     * 將 PAN 遮罩（前6碼 + 星號 + 後4碼）。
     * 若 PAN 長度不足（例如格式錯誤），仍安全處理不拋出例外。
     *
     * @param pan 原始卡號
     * @return 遮罩後的卡號
     */
    private String maskPan(String pan) {
        if (pan == null || pan.length() < 10) {
            // 長度不足時，全部遮罩（不揭露任何資訊）
            return "********************".substring(0, Math.max(pan == null ? 0 : pan.length(), 10));
        }
        String prefix = pan.substring(0, 6);
        String suffix = pan.substring(pan.length() - 4);
        int maskLength = pan.length() - 10;
        String mask = "*".repeat(maskLength);
        return prefix + mask + suffix;
    }

    /**
     * 儲存授權檢查稽核紀錄。
     *
     * @param maskedPan    遮罩後的 PAN
     * @param amountMinor  交易金額
     * @param responseCode 回應碼
     */
    private void saveAuthorizationLog(String maskedPan, Long amountMinor, String responseCode) {
        AuthorizationLog log = AuthorizationLog.builder()
                .maskedPan(maskedPan)
                .amountMinor(amountMinor)
                .responseCode(responseCode)
                .createdAt(LocalDateTime.now())
                .build();
        authorizationLogRepository.save(log);
    }

    /**
     * 將 Entity 轉換為 Response DTO。
     *
     * @param entity 授權紀錄 Entity
     * @return 授權紀錄 Response DTO
     */
    private AuthorizationLogResponse toLogResponse(AuthorizationLog entity) {
        return AuthorizationLogResponse.builder()
                .id(entity.getId())
                .maskedPan(entity.getMaskedPan())
                .amountMinor(entity.getAmountMinor())
                .responseCode(entity.getResponseCode())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}