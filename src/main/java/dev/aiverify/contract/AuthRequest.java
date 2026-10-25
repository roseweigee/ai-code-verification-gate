package dev.aiverify.contract;

/**
 * Authorization request fields used by the pre-check (subset of ISO 8583 MTI 0100).
 * Human-owned contract: AI-generated code implements against it but never changes it.
 */
public record AuthRequest(String pan, String expiry, long amountMinor, long availableMinor) {

    /** Never print the PAN (PCI DSS). Only the last four digits are shown. */
    @Override
    public String toString() {
        String masked = (pan == null || pan.length() < 4) ? "****" : "****" + pan.substring(pan.length() - 4);
        return "AuthRequest[pan=" + masked + ", expiry=" + expiry
                + ", amountMinor=" + amountMinor + ", availableMinor=" + availableMinor + "]";
    }
}
