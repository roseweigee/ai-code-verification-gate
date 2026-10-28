package dev.aiverify.ui;

import dev.aiverify.notify.Notifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Lets a visitor of the public demo page receive the emails themselves: the result of a transaction they sent, or
 * the five notifications a real approver received during one run of the gate.
 *
 * <p>Because the page is public, every send is limited: a strict address check (no line breaks, one address),
 * fixed message templates (the visitor's own text never goes into an email, the card number is masked), and rate
 * limits per address, per client and per day. Addresses are never stored or logged in full.
 */
public final class DemoMailer {

    public static final int PER_ADDRESS_HOUR = 6;
    static final int FLOW_PER_ADDRESS_DAY = 2;
    static final int PER_CLIENT_HOUR = 20;
    static final int PER_DAY = 150;
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24}$");
    private static final ZoneId TZ = ZoneId.of("Asia/Taipei");

    private final Path root;
    private final Notifier notifier;
    private final ExecutorService out = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "demo-mail");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Deque<Long>> byAddress = new HashMap<>();
    private final Map<String, Deque<Long>> flowByAddress = new HashMap<>();
    private final Map<String, Deque<Long>> byClient = new HashMap<>();
    private final Deque<Long> all = new ArrayDeque<>();

    private final int perAddressHour;

    public DemoMailer(Path root) {
        this(root, PER_ADDRESS_HOUR);
    }

    /** perAddressHour: emails one address may receive per hour (the console demo sends one per gate step). */
    public DemoMailer(Path root, int perAddressHour) {
        this.root = root;
        this.notifier = new Notifier(root);
        this.perAddressHour = perAddressHour;
    }

    boolean enabled() {
        return notifier.canDeliver();
    }

    public static boolean valid(String email) {
        return email != null && email.length() <= 254 && EMAIL.matcher(email).matches();
    }

    public static String mask(String email) {
        int at = email.indexOf('@');
        return at < 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }

    /** null when allowed (and counted), else the reason it was refused. */
    public synchronized String allow(String email, String client, int count, boolean flow) {
        long now = System.currentTimeMillis();
        String a = email.toLowerCase();
        prune(all, now, 86_400_000L);
        Deque<Long> addr = byAddress.computeIfAbsent(a, k -> new ArrayDeque<>());
        prune(addr, now, 3_600_000L);
        Deque<Long> cl = byClient.computeIfAbsent(client, k -> new ArrayDeque<>());
        prune(cl, now, 3_600_000L);
        Deque<Long> fl = flowByAddress.computeIfAbsent(a, k -> new ArrayDeque<>());
        prune(fl, now, 86_400_000L);
        if (all.size() + count > PER_DAY) {
            return "daily-limit";
        }
        if (cl.size() + count > PER_CLIENT_HOUR) {
            return "too-many";
        }
        if (flow ? fl.size() >= FLOW_PER_ADDRESS_DAY : addr.size() + count > perAddressHour) {
            return "too-many";
        }
        for (int i = 0; i < count; i++) {
            all.add(now);
            cl.add(now);
            if (!flow) {
                addr.add(now);
            }
        }
        if (flow) {
            fl.add(now);
        }
        return null;
    }

    private static void prune(Deque<Long> d, long now, long window) {
        while (!d.isEmpty() && now - d.peekFirst() > window) {
            d.pollFirst();
        }
    }

    /** The result of one transaction the visitor sent on the page. */
    void sendResult(String email, boolean zh, Map<String, String> q, int status, Map<String, Object> result,
                    Map<String, Object> release) {
        String pan = q.getOrDefault("pan", "");
        String expiry = q.getOrDefault("expiry", "");
        long amount = num(q.get("amount"));
        long available = num(q.get("available"));
        String date = q.getOrDefault("date", "").trim();
        String[] spec = specAnswer(pan.trim(), expiry.trim(), amount, available, date);
        String got = status == 200 ? String.valueOf(result.get("responseCode"))
                : result.get("exception") != null ? "HTTP 500" : "HTTP " + status;
        boolean match = got.equals(spec[0]);
        String shownPan = pan.length() <= 4 ? "****" : "**** " + pan.substring(Math.max(0, pan.length() - 4));
        String when = ZonedDateTime.now(TZ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'Taipei'"));
        StringBuilder b = new StringBuilder();
        b.append(banner(zh));
        if (zh) {
            b.append(match ? "結果符合規格。\n" : "結果和規格不一樣。\n");
        } else {
            b.append(match ? "The answer matches the spec.\n" : "The answer does not match the spec.\n");
        }
        b.append("\n");
        b.append(zh ? "交易\n" : "Transaction\n");
        b.append("  ").append(zh ? "卡號        " : "Card number   ").append(clean(shownPan)).append("\n");
        b.append("  ").append(zh ? "到期 YYMM   " : "Expiry YYMM   ").append(clean(expiry)).append("\n");
        b.append("  ").append(zh ? "金額        NT$ " : "Amount        NT$ ").append(money(amount)).append("\n");
        b.append("  ").append(zh ? "可用餘額    NT$ " : "Available     NT$ ").append(money(available)).append("\n");
        b.append("  ").append(zh ? "交易日期    " : "Date (DE 13)  ").append(date.isEmpty() ? (zh ? "（空白）" : "(missing)") : clean(date)).append("\n\n");
        b.append(zh ? "服務回覆      " : "Service answer  ").append(got).append(meaning(got, zh)).append("\n");
        b.append(zh ? "規格的答案    " : "The spec says   ").append(spec[0]).append(meaning(spec[0], zh)).append("  (").append(spec[1]).append(")\n");
        if (result.get("exception") != null) {
            b.append(zh ? "程式當掉      " : "Crash           ").append(result.get("exception"));
            Object line = result.get("line");
            if (line != null && !"0".equals(String.valueOf(line))) {
                b.append(zh ? "，第 " + line + " 行" : " at line " + line);
            }
            b.append("\n");
        }
        b.append("\n");
        b.append(zh ? "回覆你的是 Cloud Run 上的正式版本：\n" : "Answered by the production version on Cloud Run:\n");
        b.append("  Run        ").append(release.getOrDefault("runId", "?")).append("\n");
        b.append("  ").append(zh ? "核准人      " : "Approved by").append(" ").append(release.getOrDefault("approvedBy", "?")).append("\n");
        String rev = System.getenv("K_REVISION");
        if (rev != null) {
            b.append("  Revision   ").append(rev).append("\n");
        }
        b.append("  ").append(zh ? "時間       " : "Time       ").append(when).append("\n");
        b.append(footer(zh));
        String subject = "[AI Gate demo] " + (zh ? "交易結果：" : "Transaction result: ") + got + meaning(got, zh)
                + (match ? (zh ? "（符合規格）" : " (matches the spec)") : (zh ? "（和規格不同）" : " (differs from the spec)"));
        queue(email, List.of(new Notifier.Message(subject, b.toString())), 0);
    }

    /** The five notifications a real approver received during one run: blocked, waiting, rejected, waiting, approved. */
    int sendFlow(String email, boolean zh) throws java.io.IOException {
        List<Notifier.Message> ms = new ArrayList<>();
        Path dir = root.resolve("report/demo-mail");
        List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
        }
        int i = 0;
        for (Path f : files) {
            i++;
            String text = Files.readString(f);
            int nl = text.indexOf('\n');
            String subject = text.substring(0, nl).replace("[AI Gate]", "[AI Gate demo " + i + "/" + files.size() + "]");
            ms.add(new Notifier.Message(subject, flowBanner(zh, i, files.size()) + text.substring(nl + 1) + footer(zh)));
        }
        queue(email, ms, 2500);
        return ms.size();
    }

    private void queue(String email, List<Notifier.Message> ms, long gapMs) {
        out.submit(() -> {
            for (int i = 0; i < ms.size(); i++) {
                try {
                    if (i > 0 && gapMs > 0) {
                        Thread.sleep(gapMs);
                    }
                    notifier.deliver(email, ms.get(i));
                    System.out.println("Demo email " + (i + 1) + "/" + ms.size() + " sent to " + mask(email));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    System.out.println("Demo email to " + mask(email) + " not sent: " + e.getMessage());
                }
            }
        });
    }

    private static String banner(boolean zh) {
        return zh ? "這是 AI Code Verification Gate 示範頁寄出的信。你剛在示範頁輸入了這個信箱並送出一筆交易。\n\n"
                : "This email comes from the AI Code Verification Gate demo page. You entered this address there and sent a transaction.\n\n";
    }

    private static String flowBanner(boolean zh, int i, int n) {
        return zh ? "示範信 " + i + "/" + n + "：這是一次真實的品質關卡執行中，核准人收到的通知。開發者和核准人在每一步都會收到這樣的信。\n"
                + "────────────────────────────────────────\n"
                : "Demo email " + i + " of " + n + ": this is a notification the approver received during a real run of the quality gate. "
                + "The developer and the approver get one at every step.\n"
                + "────────────────────────────────────────\n";
    }

    private static String footer(boolean zh) {
        return zh ? "\n────────────────────────────────────────\n只有你在示範頁要求時才會寄出，信箱不會被儲存。\nRhosyn Wei · github.com/roseweigee/ai-code-verification-gate\n"
                : "\n────────────────────────────────────────\nSent only because you asked on the demo page. Your address is not stored.\nRhosyn Wei · github.com/roseweigee/ai-code-verification-gate\n";
    }

    private static String meaning(String code, boolean zh) {
        String[][] m = {{"00", "Approved", "核准"}, {"13", "Invalid amount", "金額無效"}, {"14", "Invalid card number", "卡號無效"},
            {"30", "Format error", "格式錯誤"}, {"51", "Insufficient funds", "餘額不足"}, {"54", "Expired card", "卡片過期"}};
        for (String[] r : m) {
            if (r[0].equals(code)) {
                return " · " + (zh ? r[2] : r[1]);
            }
        }
        return "";
    }

    /** The spec's acceptance criteria AC-01..AC-06, in order: the same reference the page shows. */
    public static String[] specAnswer(String pan, String expiry, long amount, long available, String date) {
        if (date == null || date.isEmpty()) {
            return new String[] {"30", "NF-05"};
        }
        if (pan.isEmpty() || expiry.isEmpty()) {
            return new String[] {"30", "AC-01"};
        }
        if (!expiry.matches("[0-9]{4}")) {
            return new String[] {"30", "AC-01"};
        }
        int mm = Integer.parseInt(expiry.substring(2));
        if (mm < 1 || mm > 12) {
            return new String[] {"30", "AC-01"};
        }
        if (!pan.matches("[0-9]{13,19}")) {
            return new String[] {"14", "AC-02"};
        }
        int sum = 0;
        boolean alt = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int n = pan.charAt(i) - '0';
            if (alt) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alt = !alt;
        }
        if (sum % 10 != 0) {
            return new String[] {"14", "AC-02"};
        }
        String last = YearMonth.of(2000 + Integer.parseInt(expiry.substring(0, 2)), mm).atEndOfMonth().toString();
        if (date.compareTo(last) > 0) {
            return new String[] {"54", "AC-03"};
        }
        if (amount <= 0) {
            return new String[] {"13", "AC-04"};
        }
        if (amount > available) {
            return new String[] {"51", "AC-05"};
        }
        return new String[] {"00", "AC-06"};
    }

    private static long num(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String money(long cents) {
        return String.format(java.util.Locale.ROOT, "%,d.%02d", cents / 100, Math.abs(cents % 100));
    }

    /** Visitor input inside an email: printable characters only, short. */
    private static String clean(String s) {
        String t = s.replaceAll("[^\\p{L}\\p{N} *:./-]", "?");
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }
}
