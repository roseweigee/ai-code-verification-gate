package dev.aiverify.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * The AIOps part of the canary: what an on-call engineer would otherwise do by hand after an alert.
 *
 * <ol>
 *   <li><b>Anomaly detection</b>, statistical rather than a fixed threshold: the same transactions went to both
 *       revisions, so each pair is compared. For every response code, a paired (McNemar) z-score says whether the
 *       canary moves transactions into that code more often than chance would explain.</li>
 *   <li><b>Correlation</b>: every symptom (a new 500, a code that grew) comes from the same revision in the same
 *       window, so they become one incident instead of several alerts.</li>
 *   <li><b>Root-cause drill-down</b>: the transactions whose answer changed are grouped by how they changed
 *       (for example 00 to 54). For each group, the attribute shared by most of its transactions and rare in the
 *       rest of the traffic is the likely cause. It is mapped to the spec rule it touches and to the line of code
 *       that implements that rule.</li>
 * </ol>
 * Everything here is statistics on the response data, not generative AI. The result is advice for a person.
 */
final class IncidentAnalysis {

    static final double Z = 3.0;

    private record Feature(String id, String en, String zh, String rule, BiPredicate<MonitorSim.Tx, String> test) {
    }

    private static final List<Feature> FEATURES = List.of(
            new Feature("date-missing", "the transaction date (DE 13) is missing", "交易日期（DE 13）是空的", "NF-05",
                    (t, c) -> t.date() == null),
            new Feature("expires-this-month", "the card expires this month", "卡片這個月到期", "AC-03",
                    (t, c) -> t.date() != null && ym(t.req().expiry()) != null
                            && ym(t.req().expiry()).equals(YearMonth.from(t.date()))),
            new Feature("amount-equals-balance", "the amount equals the available balance", "金額剛好等於可用餘額", "AC-05",
                    (t, c) -> t.req().amountMinor() == t.req().availableMinor()),
            new Feature("amount-over-balance", "the amount is over the available balance", "金額超過可用餘額", "AC-05",
                    (t, c) -> t.req().amountMinor() > t.req().availableMinor()),
            new Feature("already-expired", "the card expired in an earlier month", "卡片在之前的月份就過期了", "AC-03",
                    (t, c) -> t.date() != null && ym(t.req().expiry()) != null
                            && ym(t.req().expiry()).isBefore(YearMonth.from(t.date()))),
            new Feature("bad-card-number", "the card number fails the Luhn check", "卡號沒通過 Luhn 檢查", "AC-02",
                    (t, c) -> !luhn(t.req().pan())));

    private IncidentAnalysis() {
    }

    /** Builds the "incident" JSON object. crashes: "exception at line N" -> count, from the canary's 500s. */
    static String json(List<MonitorSim.Tx> tx, List<String> stable, List<String> canary, Map<String, Integer> crashes,
            Path canarySource) {
        int n = tx.size();
        List<String> src = read(canarySource);

        // 1. anomaly detection: paired z-score per response code
        List<String> codes = new ArrayList<>(List.of("00", "54", "51", "14", "30", "13", "5xx"));
        StringBuilder an = new StringBuilder();
        for (String k : codes) {
            int into = 0, outOf = 0;
            for (int i = 0; i < n; i++) {
                boolean s = k.equals(stable.get(i)), c = k.equals(canary.get(i));
                if (c && !s) {
                    into++;
                } else if (s && !c) {
                    outOf++;
                }
            }
            if (into + outOf == 0) {
                continue;
            }
            double z = (into - outOf) / Math.sqrt(into + outOf);
            an.append(an.length() == 0 ? "" : ",").append(String.format(Locale.ROOT,
                    "{\"code\":\"%s\",\"into\":%d,\"outOf\":%d,\"z\":%.1f,\"anomaly\":%b}", k, into, outOf, z,
                    "5xx".equals(k) ? into > 0 : z >= Z));
        }

        // 2 + 3. group the changed transactions by how they changed, then drill down into each group
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (!stable.get(i).equals(canary.get(i))) {
                groups.computeIfAbsent(stable.get(i) + ">" + canary.get(i), x -> new ArrayList<>()).add(i);
            }
        }
        int changed = groups.values().stream().mapToInt(List::size).sum();
        List<Map.Entry<String, List<Integer>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort((a, b) -> {
            boolean ca = a.getKey().endsWith(">5xx"), cb = b.getKey().endsWith(">5xx");
            return ca != cb ? (ca ? -1 : 1) : b.getValue().size() - a.getValue().size();
        });
        StringBuilder gs = new StringBuilder();
        for (Map.Entry<String, List<Integer>> g : ordered) {
            String[] fromTo = g.getKey().split(">");
            List<Integer> idx = g.getValue();
            Feature best = null;
            double bestCov = 0, bestBase = 0, bestScore = 0;
            for (Feature f : FEATURES) {
                int in = 0, all = 0;
                for (int i : idx) {
                    if (f.test().test(tx.get(i), canary.get(i))) {
                        in++;
                    }
                }
                for (int i = 0; i < n; i++) {
                    if (f.test().test(tx.get(i), canary.get(i))) {
                        all++;
                    }
                }
                double cov = in * 1.0 / idx.size(), base = all * 1.0 / n;
                double score = cov - base;          // shared by the group, rare in the rest of the traffic
                if (cov >= 0.8 && score > bestScore) {
                    best = f;
                    bestCov = cov;
                    bestBase = base;
                    bestScore = score;
                }
            }
            int line = 0;
            String where = "";
            if ("5xx".equals(fromTo[1]) && !crashes.isEmpty()) {
                String c = crashes.keySet().iterator().next();
                where = c;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("line (\\d+)").matcher(c);
                if (m.find()) {
                    line = Integer.parseInt(m.group(1));
                }
            } else if (best != null) {
                for (int i = 0; i < src.size(); i++) {
                    if (src.get(i).contains("// " + best.rule())) {
                        line = i + 1;
                        break;
                    }
                }
            }
            int from = "5xx".equals(fromTo[1]) ? Math.max(1, line - 1) : line;
            if (!"5xx".equals(fromTo[1]) && line > 0) {
                // the rule's comment line starts the block; the hit is the first condition under it
                for (int l = line + 1; l <= Math.min(src.size(), line + 3); l++) {
                    if (src.get(l - 1).contains("if (")) {
                        line = l;
                        break;
                    }
                }
            }
            StringBuilder code = new StringBuilder();
            if (line > 0 && line <= src.size()) {
                int to = Math.min(src.size(), from + 3);
                for (int l = from; l <= to; l++) {
                    code.append(code.length() == 0 ? "" : ",").append("{\"n\":").append(l).append(",\"t\":\"")
                            .append(esc(src.get(l - 1))).append("\",\"hit\":").append(l == line).append("}");
                }
            }
            gs.append(gs.length() == 0 ? "" : ",").append(String.format(Locale.ROOT,
                    "{\"from\":\"%s\",\"to\":\"%s\",\"count\":%d", fromTo[0], fromTo[1], idx.size()));
            if (best != null) {
                gs.append(String.format(Locale.ROOT,
                        ",\"feature\":\"%s\",\"en\":\"%s\",\"zh\":\"%s\",\"rule\":\"%s\",\"coverage\":%.3f,\"base\":%.3f",
                        best.id(), esc(best.en()), esc(best.zh()), best.rule(), bestCov, bestBase));
            }
            if (!where.isEmpty()) {
                gs.append(",\"crash\":\"").append(esc(where)).append("\"");
            }
            gs.append(",\"line\":").append(line).append(",\"code\":[").append(code).append("]}");
        }
        return "{\"changed\":" + changed + ",\"n\":" + n + ",\"anomalies\":[" + an + "],\"groups\":[" + gs + "]}";
    }

    /** Plain-text version for the GitHub Actions log. */
    static void print(String json) {
        System.out.println();
        System.out.println("Incident analysis (AIOps): anomaly detection, correlation, root cause");
        System.out.println("  " + json);
    }

    private static YearMonth ym(String expiry) {
        if (expiry == null || !expiry.matches("\\d{4}")) {
            return null;
        }
        int m = Integer.parseInt(expiry.substring(2));
        return m < 1 || m > 12 ? null : YearMonth.of(2000 + Integer.parseInt(expiry.substring(0, 2)), m);
    }

    private static boolean luhn(String pan) {
        if (pan == null || !pan.matches("\\d{13,19}")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < pan.length(); i++) {
            int d = pan.charAt(pan.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                d = d * 2 > 9 ? d * 2 - 9 : d * 2;
            }
            sum += d;
        }
        return sum % 10 == 0;
    }

    private static List<String> read(Path p) {
        try {
            return p != null && Files.exists(p) ? Files.readAllLines(p) : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** JSON string escape; non-ASCII as \\uXXXX, so the log and the base64 annotation stay ASCII. */
    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') {
                b.append('\\').append(ch);
            } else if (ch == '\t') {
                b.append("    ");
            } else if (ch < 0x20 || ch > 0x7e) {
                b.append(String.format("\\u%04x", (int) ch));
            } else {
                b.append(ch);
            }
        }
        return b.toString();
    }
}
