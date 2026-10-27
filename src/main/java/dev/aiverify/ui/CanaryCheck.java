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

    private static final Pattern CODE = Pattern.compile("\"responseCode\"\\s*:\\s*\"(\\d\\d)\"");

    private static final Pattern EXC = Pattern.compile("\"exception\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern LINE = Pattern.compile("\"line\"\\s*:\\s*(\\d+)");
    /** "exception at line N" -> count, from the canary's HTTP 500 bodies. */
    private static final Map<String, Integer> CRASHES = new java.util.concurrent.ConcurrentHashMap<>();

    private CanaryCheck() {
    }

    public static int run(String stableUrl, String canaryUrl, int n) throws Exception {
        List<MonitorSim.Tx> tx = MonitorSim.traffic().subList(0, n);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        warm(http, stableUrl);
        warm(http, canaryUrl);
        Map<String, Integer> stable = send(http, stableUrl, tx);
        CRASHES.clear();
        Map<String, Integer> canary = send(http, canaryUrl, tx);

        System.out.println("Canary analysis: " + n + " identical transactions sent to each revision");
        System.out.println("  stable: " + stableUrl);
        System.out.println("  canary: " + canaryUrl);
        System.out.println();
        System.out.printf("  %-6s %10s %10s%n", "code", "stable", "canary");
        for (String k : new String[] {"00", "54", "51", "14", "30", "5xx", "other"}) {
            double s = share(stable, k, n), c = share(canary, k, n);
            System.out.printf("  %-6s %9.1f%% %9.1f%%%n", k, s * 100, c * 100);
        }
        StringBuilder js = new StringBuilder("{\"n\":" + n + ",\"rows\":[");
        String[] keys = {"00", "54", "51", "14", "30", "5xx"};
        for (int i = 0; i < keys.length; i++) {
            js.append(i == 0 ? "" : ",").append(String.format(java.util.Locale.ROOT, "{\"code\":\"%s\",\"stable\":%.4f,\"canary\":%.4f}",
                    keys[i], share(stable, keys[i], n), share(canary, keys[i], n)));
        }
        // every metric that breached its rule; crashes first, because a 500 is the most visible harm
        List<String> breached = new ArrayList<>();
        for (String k : new String[] {"5xx", "54", "51", "14", "30"}) {
            double s0 = share(stable, k, n), c0 = share(canary, k, n);
            boolean j = "5xx".equals(k) ? c0 > 0.003 && c0 > s0 : c0 >= 2 * s0 && c0 - s0 >= 0.015;
            if (j) {
                breached.add(k);
            }
        }
        js.append("],\"verdict\":\"").append(breached.isEmpty() ? "PROMOTE" : "ROLLBACK").append("\"");
        if (!breached.isEmpty()) {
            js.append(",\"metric\":\"").append(breached.get(0)).append("\",\"metrics\":[");
            for (int i = 0; i < breached.size(); i++) {
                js.append(i == 0 ? "" : ",").append('"').append(breached.get(i)).append('"');
            }
            js.append("]");
        }
        if (!CRASHES.isEmpty()) {
            js.append(",\"crashes\":[");
            int i = 0;
            for (Map.Entry<String, Integer> e : CRASHES.entrySet()) {
                js.append(i++ == 0 ? "" : ",").append("{\"what\":\"").append(e.getKey().replace("\"", "'"))
                        .append("\",\"count\":").append(e.getValue()).append("}");
            }
            js.append("]");
        }
        js.append("}");
        System.out.println();
        System.out.println("CANARY_RESULT " + js);
        System.out.println();
        if (breached.isEmpty()) {
            System.out.println("Verdict: PROMOTE. The canary's response-code mix matches the stable revision.");
            return 0;
        }
        System.out.println("Verdict: ROLL BACK.");
        for (String k : breached) {
            System.out.printf("  %s: %.1f%% on the stable revision, %.1f%% on the canary%n",
                    "5xx".equals(k) ? "HTTP 500 (crash)" : "code " + k, share(stable, k, n) * 100, share(canary, k, n) * 100);
        }
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
                                Matcher em = EXC.matcher(r.body());
                                if (em.find()) {
                                    Matcher lm = LINE.matcher(r.body());
                                    CRASHES.merge(em.group(1) + (lm.find() && !"0".equals(lm.group(1)) ? " at line " + lm.group(1) : ""), 1, Integer::sum);
                                    return "5xx";
                                }
                                if (attempt >= 2) {
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
