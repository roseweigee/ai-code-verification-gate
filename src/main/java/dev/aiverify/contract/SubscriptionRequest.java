package dev.aiverify.contract;

import java.time.LocalDate;

/**
 * A fund subscription order, as the pre-check sees it (wealth management scenario).
 * Human-owned contract: AI-generated code implements against it but never changes it.
 *
 * @param clientRiskLevel KYC risk profile of the client, "C1" (most conservative) to "C5"
 * @param fundRiskLevel   risk rating of the fund, "RR1" (lowest) to "RR5"
 * @param kycAssessedOn   date the client's KYC risk assessment was completed
 * @param amountMinor     order amount in minor units (1/100 of the fund currency)
 * @param minimumMinor    the fund's minimum subscription amount in minor units
 * @param holdingMinor    the client's current holding in this fund, in minor units
 * @param portfolioMinor  the client's total portfolio value, including holdingMinor, in minor units
 */
public record SubscriptionRequest(String clientRiskLevel, String fundRiskLevel, LocalDate kycAssessedOn,
                                  long amountMinor, long minimumMinor, long holdingMinor, long portfolioMinor) {
}
