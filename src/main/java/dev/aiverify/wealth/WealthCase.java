package dev.aiverify.wealth;

import dev.aiverify.contract.SubscriptionRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One acceptance test for the wealth scenario, expressed as data: order -> expected result code.
 * Like the card-payment tests, tests are data (not code), so an AI can propose them without writing
 * executable code and a human can review them as a table.
 */
public record WealthCase(String id, String criterion, String description, String origin,
                         String clientRiskLevel, String fundRiskLevel, LocalDate kycAssessedOn,
                         long amountMinor, long minimumMinor, long holdingMinor, long portfolioMinor,
                         LocalDate tradeDate, String expected, String since) {

    public boolean includedIn(String version) {
        if (since == null || version == null || version.equals("latest")) {
            return true;
        }
        String[] x = since.split("\\.");
        String[] y = version.split("\\.");
        for (int k = 0; k < Math.max(x.length, y.length); k++) {
            int p = k < x.length ? Integer.parseInt(x[k]) : 0;
            int q = k < y.length ? Integer.parseInt(y[k]) : 0;
            if (p != q) {
                return p < q;
            }
        }
        return true;
    }

    public SubscriptionRequest request() {
        return new SubscriptionRequest(clientRiskLevel, fundRiskLevel, kycAssessedOn,
                amountMinor, minimumMinor, holdingMinor, portfolioMinor);
    }

    @SuppressWarnings("unchecked")
    public static List<WealthCase> fromJson(Object parsed, String origin) {
        List<Object> items;
        if (parsed instanceof Map<?, ?> m && m.get("cases") instanceof List<?> l) {
            items = (List<Object>) l;
        } else if (parsed instanceof List<?> l) {
            items = (List<Object>) l;
        } else {
            throw new IllegalArgumentException("Expected {\"cases\": [...]} or a JSON array");
        }
        List<WealthCase> out = new ArrayList<>();
        for (Object o : items) {
            Map<String, Object> c = (Map<String, Object>) o;
            out.add(new WealthCase(str(c.get("id")), str(c.get("criterion")), str(c.get("description")), origin,
                    str(c.get("clientRiskLevel")), str(c.get("fundRiskLevel")), date(c.get("kycAssessedOn")),
                    num(c.get("amountMinor")), num(c.get("minimumMinor")), num(c.get("holdingMinor")),
                    num(c.get("portfolioMinor")), date(c.get("tradeDate")), str(c.get("expected")),
                    str(c.get("since"))));
        }
        return out;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("criterion", criterion);
        m.put("description", description);
        m.put("origin", origin);
        m.put("clientRiskLevel", clientRiskLevel);
        m.put("fundRiskLevel", fundRiskLevel);
        m.put("kycAssessedOn", kycAssessedOn == null ? null : kycAssessedOn.toString());
        m.put("amountMinor", amountMinor);
        m.put("minimumMinor", minimumMinor);
        m.put("holdingMinor", holdingMinor);
        m.put("portfolioMinor", portfolioMinor);
        m.put("tradeDate", tradeDate == null ? null : tradeDate.toString());
        m.put("expected", expected);
        if (since != null) {
            m.put("since", since);
        }
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static LocalDate date(Object o) {
        return o == null ? null : LocalDate.parse(o.toString());
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(o.toString());
    }
}
