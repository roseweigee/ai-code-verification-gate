package dev.aiverify.ui;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canary analysis against two live Cloud Run revisions: the same transactions go to the stable revision and to the
 * canary, over HTTP. Like the monitor, it knows nothing about the spec: it compares the share of each response code
 * and of server errors. Exit code 0 = promote, 3 = roll back.
 */
public final class CanaryCheck {

    private static final String[] WATCH = {"54", "51", "14", "30", "5xx"};
    private static final Pattern CODE = Pattern.compile("\"responseCode\"\\s*:\\s*\"(\\d\\d)\"");

    private CanaryCheck() {
    }

    public static int run(String stableUrl, String canaryUrl, int n) throws Exception {
        List<MonitorSim.Tx> tx = MonitorSim.traffic().subList(0, n);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        warm(http, stableUrl);
        warm(http, canaryUrl);
        Map<String, Integer> stable = send(http, stableUrl, tx);
        Map<String, Integer> canary = send(http, canaryUrl, tx);

        System.out.println("Canary analysis: " + n + " identical transactions sent to each revision");
        System.out.println("  stable: " + stableUrl);
        System.out.println("  canary: " + canaryUrl);
        System.out.println();
        System.out.printf("  %-6s %10s %10s%n", "code", "stable", "canary");
        String worst = null;
        double worstGap = 0;
        for (String k : new String[] {"00", "54", "51", "14", "30", "5xx", "other"}) {
            double s = share(stable, k, n), c = share(canary, k, n);
            System.out.printf("  %-6s %9.1f%% %9.1f%%%n", k, s * 100, c * 100);
        }
        for (String k : WATCH) {
            double s = share(stable, k, n), c = share(canary, k, n);
            boolean jump = "5xx".equals(k) ? c > 0.003 && c > s : c >= 2 * s && c - s >= 0.015;
            if (jump && c - s > worstGap) {
                worst = k;
                worstGap = c - s;
            }
        }
        StringBuilder js = new StringBuilder("{\"n\":" + n + ",\"rows\":[");
        String[] keys = {"00", "54", "51", "14", "30", "5xx"};
        for (int i = 0; i < keys.length; i++) {
            js.append(i == 0 ? "" : ",").append(String.format(java.util.Locale.ROOT, "{\"code\":\"%s\",\"stable\":%.4f,\"canary\":%.4f}",
                    keys[i], share(stable, keys[i], n), share(canary, keys[i], n)));
        }
        String verdictMetric = null;
        double gap = 0;
        for (String k : WATCH) {
            double s0 = share(stable, k, n), c0 = share(canary, k, n);
            boolean j = "5xx".equals(k) ? c0 > 0.003 && c0 > s0 : c0 >= 2 * s0 && c0 - s0 >= 0.015;
            if (j && c0 - s0 > gap) {
                verdictMetric = k;
                gap = c0 - s0;
            }
        }
        js.append("],\"verdict\":\"").append(verdictMetric == null ? "PROMOTE" : "ROLLBACK").append("\"")
                .append(verdictMetric == null ? "" : ",\"metric\":\"" + verdictMetric + "\"").append("}");
        System.out.println();
        System.out.println("CANARY_RESULT " + js);
        System.out.println();
        if (worst == null) {
            System.out.println("Verdict: PROMOTE. The canary's response-code mix matches the stable revision.");
            return 0;
        }
        System.out.printf("Verdict: ROLL BACK. Code %s jumped from %.1f%% on the stable revision to %.1f%% on the canary.%n",
                worst, share(stable, worst, n) * 100, share(canary, worst, n) * 100);
        System.out.println("No exception was needed to see it: only the pattern changed.");
        return 3;
    }

    private static double share(Map<String, Integer> m, String k, int n) {
        return m.getOrDefault(k, 0) * 1.0 / n;
    }

    private static void warm(HttpClient http, String base) {
        for (int i = 0; i < 3; i++) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/healthz"))
                        .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // cold start: try again
            }
        }
    }

    private static Map<String, Integer> send(HttpClient http, String base, List<MonitorSim.Tx> tx) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (MonitorSim.Tx t : tx) {
                String q = "pan=" + enc(t.req().pan()) + "&expiry=" + enc(t.req().expiry())
                        + "&amount=" + t.req().amountMinor() + "&available=" + t.req().availableMinor()
                        + "&date=" + (t.date() == null ? "" : t.date().toString());
                fs.add(ex.submit(() -> {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/authorize?" + q))
                            .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.noBody()).build();
                    for (int attempt = 0; ; attempt++) {
                        try {
                            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                            if (r.statusCode() >= 500) {
                                if (r.body().contains("\"exception\"") || attempt >= 2) {
                                    return "5xx";
                                }
                                continue;   // platform hiccup, not the code: try again
                            }
                            Matcher m = CODE.matcher(r.body());
                            return m.find() ? m.group(1) : "other";
                        } catch (java.io.IOException e) {
                            if (attempt >= 2) {
                                return "other";
                            }
                        }
                    }
                }));
            }
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Future<String> f : fs) {
                String code = f.get();
                out.merge(code, 1, Integer::sum);
            }
            return out;
        } finally {
            ex.shutdownNow();
        }
    }

    private static String enc(String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
