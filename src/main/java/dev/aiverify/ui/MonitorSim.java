package dev.aiverify.ui;

import dev.aiverify.contract.AuthRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Post-release monitoring, simulated: "what if a bug had slipped past the tests and reached production?"
 *
 * <p>Replays one day of realistic card traffic (seeded, so the demo is repeatable). The stable version serves the
 * first minutes; then the canary takes over. The monitor knows nothing about the spec: it only compares the share of
 * each response code, and of crashes, with the baseline the stable version set. When a share jumps, it rolls back
 * to the stable version automatically. This is how production catches what testing missed.
 */
final class MonitorSim {

    static final int MINUTES = 12;
    static final int PER_MINUTE = 300;
    static final int CANARY_AT = 4;
    static final String[] WATCH = {"54", "51", "14", "30", "5xx"};
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    private static final String[] PANS = {"4111111111111111", "5555555555554444", "4012888888881881", "6011111111111117",
        "5105105105105100"};

    record Tx(AuthRequest req, LocalDate date) {
    }

    static List<Tx> traffic() {
        Random r = new Random(20261027L);
        List<Tx> out = new ArrayList<>();
        for (int i = 0; i < MINUTES * PER_MINUTE; i++) {
            String pan = PANS[r.nextInt(PANS.length)];
            if (r.nextDouble() < 0.004) {
                pan = pan.substring(0, pan.length() - 1) + (char) ('0' + (pan.charAt(pan.length() - 1) - '0' + 1) % 10);
            }
            double e = r.nextDouble();
            String expiry;
            if (e < 0.025) {
                expiry = "2610";                                   // card in its last month: still valid (AC-03)
            } else if (e < 0.04) {
                expiry = "26" + String.format("%02d", 1 + r.nextInt(9)); // expired earlier this year
            } else {
                int months = 1 + r.nextInt(50);                      // valid for 1 to 50 more months
                int y = 26 + (10 + months - 1) / 12;
                int m = (10 + months - 1) % 12 + 1;
                expiry = String.format("%02d%02d", y, m);
            }
            long available = 1_000 + r.nextInt(500_000);
            double a = r.nextDouble();
            long amount = a < 0.03 ? available + 1 + r.nextInt(50_000)
                    : a < 0.04 ? available
                    : 100 + (long) (r.nextDouble() * (available - 100));
            // about 1% of messages arrive without DE 13 (local transaction date), e.g. one acquirer channel that omits it
            LocalDate date = r.nextDouble() < 0.01 ? null : TODAY;
            out.add(new Tx(new AuthRequest(pan, expiry, amount, available), date));
        }
        return out;
    }

    static Map<String, Object> run(LiveService live, String stable, String canary) throws Exception {
        List<Tx> tx = traffic();
        Map<String, Double> baseline = new LinkedHashMap<>();
        List<Map<String, Object>> minutes = new ArrayList<>();
        String serving = stable;
        Integer alertAt = null;
        String alertMetric = null;
        int wrongDuringCanary = 0;
        int canaryTx = 0;
        for (int m = 0; m < MINUTES; m++) {
            if (m == CANARY_AT && alertAt == null) {
                serving = canary;
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String k : WATCH) {
                counts.put(k, 0);
            }
            counts.put("00", 0);
            for (int i = m * PER_MINUTE; i < (m + 1) * PER_MINUTE; i++) {
                Tx t = tx.get(i);
                String rc = live.answer(serving, t.req(), t.date());
                counts.merge(rc, 1, Integer::sum);
                if (serving.equals(canary)) {
                    canaryTx++;
                    if (!rc.equals(live.answer(stable, t.req(), t.date()))) {
                        wrongDuringCanary++;
                    }
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("minute", m + 1);
            row.put("version", serving.equals(stable) ? "stable" : "canary");
            Map<String, Double> rates = new LinkedHashMap<>();
            for (String k : WATCH) {
                rates.put(k, counts.get(k) * 1.0 / PER_MINUTE);
            }
            row.put("rates", rates);
            row.put("approved", counts.get("00") * 1.0 / PER_MINUTE);
            if (m < CANARY_AT) {
                for (String k : WATCH) {
                    baseline.merge(k, rates.get(k) / CANARY_AT, Double::sum);
                }
            } else if (serving.equals(canary) && alertAt == null) {
                // anomaly rule: a share at least double its baseline and 1.5 points higher; any crash share over 0.3%
                for (String k : WATCH) {
                    double base = baseline.getOrDefault(k, 0.0);
                    double now = rates.get(k);
                    boolean jump = "5xx".equals(k) ? now > 0.003 : now >= 2 * base && now - base >= 0.015;
                    if (jump && (alertMetric == null || now - base > rates.get(alertMetric) - baseline.get(alertMetric))) {
                        alertMetric = k;
                    }
                }
                if (alertMetric != null) {
                    alertAt = m + 1;
                    row.put("alert", alertMetric);
                    serving = stable;      // automatic rollback: the next minute is served by the stable version
                    row.put("rollback", true);
                }
            }
            minutes.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stable", stable.replace("run:", ""));
        out.put("canary", canary.replace("run:", ""));
        out.put("perMinute", PER_MINUTE);
        out.put("canaryAt", CANARY_AT + 1);
        out.put("baseline", baseline);
        out.put("minutes", minutes);
        out.put("alertAt", alertAt);
        out.put("alertMetric", alertMetric);
        out.put("canaryTransactions", canaryTx);
        out.put("affected", wrongDuringCanary);
        return out;
    }
}
