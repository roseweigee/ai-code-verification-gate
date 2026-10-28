package dev.aiverify.testing;

import dev.aiverify.contract.AuthRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One acceptance test, expressed as data: input -> expected ISO 8583 response code.
 * Tests are data (not code) so an AI can propose them without writing executable code,
 * and a human can review them as a table.
 *
 * @param origin "golden" (human-written, held out from the AI author) or "ai" (generated from the spec only)
 */
public record TestCase(String id, String criterion, String description, String origin,
                       String pan, String expiry, long amountMinor, long availableMinor,
                       LocalDate transactionDate, String expected, String since) {

    public TestCase(String id, String criterion, String description, String origin, String pan, String expiry,
                    long amountMinor, long availableMinor, LocalDate transactionDate, String expected) {
        this(id, criterion, description, origin, pan, expiry, amountMinor, availableMinor, transactionDate, expected, null);
    }

    /** True if this case belongs to the given golden-set version ("latest" includes every case). */
    public boolean includedIn(String version) {
        if (since == null || version == null || version.equals("latest")) {
            return true;
        }
        return compareVersions(since, version) <= 0;
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int k = 0; k < Math.max(x.length, y.length); k++) {
            int p = k < x.length ? Integer.parseInt(x[k]) : 0;
            int q = k < y.length ? Integer.parseInt(y[k]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    public AuthRequest request() {
        return new AuthRequest(pan, expiry, amountMinor, availableMinor);
    }

    @SuppressWarnings("unchecked")
    public static List<TestCase> fromJson(Object parsed, String origin) {
        List<Object> items;
        if (parsed instanceof Map<?, ?> m && m.get("cases") instanceof List<?> l) {
            items = (List<Object>) l;
        } else if (parsed instanceof List<?> l) {
            items = (List<Object>) l;
        } else {
            throw new IllegalArgumentException("Expected {\"cases\": [...]} or a JSON array");
        }
        List<TestCase> out = new ArrayList<>();
        int skipped = 0;
        for (Object o : items) {
            Map<String, Object> c = (Map<String, Object>) o;
            try {
            out.add(new TestCase(
                    str(c.get("id")), str(c.get("criterion")), str(c.get("description")), origin,
                    (String) c.get("pan"), (String) c.get("expiry"),
                    num(c.get("amountMinor")), num(c.get("availableMinor")),
                    c.get("transactionDate") == null ? null : LocalDate.parse(str(c.get("transactionDate"))), str(c.get("expected")), str(c.get("since"))));
            } catch (RuntimeException bad) {
                skipped++; // a malformed case (e.g. a missing amount) is left out, never guessed
            }
        }
        if (skipped > 0) {
            System.out.println("  note: " + skipped + " malformed " + origin + " test case(s) left out");
        }
        return out;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("criterion", criterion);
        m.put("description", description);
        m.put("origin", origin);
        m.put("pan", maskPan(pan));
        m.put("expiry", expiry);
        m.put("amountMinor", amountMinor);
        m.put("availableMinor", availableMinor);
        m.put("transactionDate", transactionDate == null ? null : transactionDate.toString()); // a missing date is a valid test (NF-05)
        m.put("expected", expected);
        if (since != null) {
            m.put("since", since);
        }
        return m;
    }

    /** Reports never show a full PAN, even a test PAN, so the habit is the same in production. */
    public static String maskPan(String pan) {
        if (pan == null) {
            return null;
        }
        if (pan.length() <= 10) {
            return "*".repeat(pan.length());
        }
        return pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(o.toString());
    }
}
