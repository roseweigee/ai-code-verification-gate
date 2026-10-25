package dev.aiverify.contract;

import java.time.LocalDate;

/** The contract the AI author must implement. See spec/authorization-precheck.md. */
public interface AuthorizationPrecheck {

    /** Returns an ISO 8583 response code (field 39), e.g. "00", "14", "30", "51", "54", "13". */
    String check(AuthRequest request, LocalDate transactionDate);
}
