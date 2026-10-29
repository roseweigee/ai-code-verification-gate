package dev.aiverify.contract;

import java.time.LocalDate;

/** The contract the AI author must implement for the wealth scenario. See spec/subscription-precheck.md. */
public interface SubscriptionPrecheck {

    /** Returns one result code: "OK", "FMT", "KYC", "UNS", "AMT" or "CON". */
    String check(SubscriptionRequest request, LocalDate tradeDate);
}
