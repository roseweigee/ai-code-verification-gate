package dev.aiverify.candidate;

import dev.aiverify.contract.SubscriptionPrecheck;
import dev.aiverify.contract.SubscriptionRequest;
import java.time.LocalDate;
import java.util.List;

public class SubscriptionPrecheckImpl implements SubscriptionPrecheck {

    private static final String ACCEPTED = "OK";
    private static final String FORMAT_ERROR = "FMT";
    private static final String KYC_EXPIRED = "KYC";
    private static final String UNSUITABLE = "UNS";
    private static final String INVALID_AMOUNT = "AMT";
    private static final String CONCENTRATION = "CON";

    /** The only valid spellings. Exact match: no parsing, so "C03", "C+3" or full-width digits are rejected. */
    private static final List<String> CLIENT_LEVELS = List.of("C1", "C2", "C3", "C4", "C5");
    private static final List<String> FUND_LEVELS = List.of("RR1", "RR2", "RR3", "RR4", "RR5");

    @Override
    public String check(SubscriptionRequest request, LocalDate tradeDate) {
        // NF-05 auto-fix by the gate: a null input is a format error (FMT), never a crash
        if (request == null || tradeDate == null) {
            return "FMT";
        }
        // AC-01 format: required fields, then exact level codes only (List.of does not accept null lookups)
        if (request.clientRiskLevel() == null || request.fundRiskLevel() == null || request.kycAssessedOn() == null) {
            return FORMAT_ERROR;
        }
        int client = CLIENT_LEVELS.indexOf(request.clientRiskLevel());
        int fund = FUND_LEVELS.indexOf(request.fundRiskLevel());
        if (client < 0 || fund < 0) {
            return FORMAT_ERROR;
        }

        // AC-02 valid up to and including the same date one year later
        LocalDate validUntil = request.kycAssessedOn().plusYears(1);
        if (tradeDate.isAfter(validUntil)) {
            return KYC_EXPIRED;
        }

        // AC-03 a fund equal to the client's level is allowed
        if (fund > client) {
            return UNSUITABLE;
        }

        // AC-04 amount
        long amount = request.amountMinor();
        if (amount <= 0 || amount < request.minimumMinor()) {
            return INVALID_AMOUNT;
        }

        // AC-05 concentration: at most 30% of the portfolio after the order, compared in whole minor units
        long holdingAfter = request.holdingMinor() + amount;
        long portfolioAfter = request.portfolioMinor() + amount;
        if (holdingAfter * 100 > portfolioAfter * 30) {
            return CONCENTRATION;
        }

        return ACCEPTED;
    }
}
