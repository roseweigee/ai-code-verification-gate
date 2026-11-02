package dev.aiverify.notify;

import dev.aiverify.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Who receives which email, kept in {@code config/recipients.json} and edited on the console's notification page.
 *
 * <pre>{"recipients": [{"email": "a@bank.com", "name": "Approver", "events": ["ready", "decision", "evidence"]}]}</pre>
 *
 * Events: {@code blocked} (the gate stopped a change), {@code ready} (waiting for a named approver),
 * {@code decision} (approved or rejected), {@code evidence} (the release evidence pack, attached).
 * {@code notify.to} in notify.properties still receives everything.
 */
public final class Recipients {

    public static final List<String> EVENTS = List.of("blocked", "ready", "decision", "evidence");
    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24}$");

    public record Recipient(String email, String name, List<String> events) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("email", email);
            m.put("name", name);
            m.put("events", events);
            return m;
        }
    }

    private Recipients() {
    }

    public static Path file(Path root) {
        return root.resolve("config/recipients.json");
    }

    public static boolean valid(String email) {
        return email != null && email.length() <= 254 && EMAIL.matcher(email).matches();
    }

    @SuppressWarnings("unchecked")
    public static List<Recipient> load(Path root) {
        List<Recipient> out = new ArrayList<>();
        Path f = file(root);
        if (!Files.exists(f)) {
            return out;
        }
        try {
            Object list = Json.parseObject(Files.readString(f)).get("recipients");
            if (list instanceof List<?> l) {
                for (Object o : l) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    String email = String.valueOf(m.getOrDefault("email", "")).trim();
                    if (!valid(email)) {
                        continue;
                    }
                    List<String> ev = new ArrayList<>();
                    if (m.get("events") instanceof List<?> e) {
                        for (Object x : e) {
                            if (EVENTS.contains(String.valueOf(x))) {
                                ev.add(String.valueOf(x));
                            }
                        }
                    }
                    out.add(new Recipient(email, clean(m.get("name")), ev));
                }
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("Email: could not read config/recipients.json (" + e.getMessage() + ").");
        }
        return out;
    }

    /**
     * Validates and saves the list. Throws IllegalArgumentException with a short reason for the page:
     * {@code invalid:<email>}, {@code duplicate:<email>}, {@code too-many:<max>}.
     */
    @SuppressWarnings("unchecked")
    public static List<Recipient> save(Path root, List<Object> list, int max) throws IOException {
        List<Recipient> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : list) {
            Map<String, Object> m = (Map<String, Object>) o;
            String email = String.valueOf(m.getOrDefault("email", "")).trim();
            if (email.isEmpty()) {
                continue;
            }
            if (!valid(email)) {
                throw new IllegalArgumentException("invalid:" + email);
            }
            if (!seen.add(email.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("duplicate:" + email);
            }
            List<String> ev = new ArrayList<>();
            if (m.get("events") instanceof List<?> e) {
                for (String k : EVENTS) {
                    if (e.contains(k)) {
                        ev.add(k);
                    }
                }
            }
            out.add(new Recipient(email, clean(m.get("name")), ev));
        }
        if (out.size() > max) {
            throw new IllegalArgumentException("too-many:" + max);
        }
        List<Object> rows = new ArrayList<>();
        out.forEach(r -> rows.add(r.toMap()));
        Files.createDirectories(file(root).getParent());
        Files.writeString(file(root), Json.write(Map.of("recipients", rows)));
        return out;
    }

    /** The addresses that want this event. */
    public static List<String> forEvent(Path root, String event) {
        List<String> out = new ArrayList<>();
        for (Recipient r : load(root)) {
            if (r.events().contains(event)) {
                out.add(r.email());
            }
        }
        return out;
    }

    public static List<Object> toList(List<Recipient> rs) {
        List<Object> rows = new ArrayList<>();
        rs.forEach(r -> rows.add(r.toMap()));
        return rows;
    }

    private static String clean(Object o) {
        String s = o == null ? "" : String.valueOf(o);
        s = s.replaceAll("[\\r\\n<>\"]", " ").trim();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }
}
