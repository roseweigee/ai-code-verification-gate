package dev.aiverify.notify;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Email notifications for gate events, with no third-party library.
 *
 * <p>Every notification is first saved to {@code runs/outbox/} as an .eml file, so the message exists even
 * without a network. It is then sent over SMTP when {@code notify.properties} (or environment variables) name
 * a recipient and an SMTP account. A failure to send never fails the command that triggered it: the gate's
 * decision must not depend on a mail server.
 *
 * <p>Settings (file {@code notify.properties} in the project root, or the environment variable in brackets):
 * <ul>
 *   <li>{@code notify.to} [NOTIFY_TO]: recipients, comma separated</li>
 *   <li>{@code smtp.user} [SMTP_USER] and {@code smtp.password} [SMTP_PASSWORD]: for Gmail, an app password</li>
 *   <li>{@code smtp.host} [SMTP_HOST], default smtp.gmail.com; {@code smtp.port} [SMTP_PORT], default 465</li>
 *   <li>{@code smtp.security} [SMTP_SECURITY]: ssl (default), starttls or none</li>
 *   <li>{@code notify.enabled} [NOTIFY_ENABLED]: false turns sending off; the outbox is still written</li>
 * </ul>
 */
public final class Notifier {

    private static final int TIMEOUT_MS = 8000;

    private final Path root;
    private final Properties cfg = new Properties();

    public Notifier(Path root) {
        this.root = root;
        Path file = root.resolve("notify.properties");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                cfg.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.out.println("Email: could not read notify.properties (" + e.getMessage() + ").");
            }
        }
    }

    /** A message to send. The body is plain text. */
    public record Message(String subject, String body) {
    }

    /** Saves the message to the outbox, sends it if configured, and prints one line saying what happened. */
    public void send(Message m) {
        String to = get("notify.to", "NOTIFY_TO", "");
        String user = get("smtp.user", "SMTP_USER", "");
        String from = user.isBlank() ? "ai-gate@localhost" : user;
        String eml = toEml(from, to.isBlank() ? "(not configured)" : to, m);
        Path saved = null;
        try {
            Path outbox = root.resolve("runs/outbox");
            Files.createDirectories(outbox);
            String stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            saved = outbox.resolve(stamp + ".eml");
            Files.writeString(saved, eml);
        } catch (IOException e) {
            System.out.println("Email: could not save to the outbox (" + e.getMessage() + ").");
        }
        String where = saved == null ? "" : " Saved to " + root.relativize(saved) + ".";
        if (to.isBlank()) {
            System.out.println("Email: no recipient set in notify.properties, so nothing was sent." + where);
            return;
        }
        if ("false".equalsIgnoreCase(get("notify.enabled", "NOTIFY_ENABLED", "true"))) {
            System.out.println("Email: sending is turned off (notify.enabled=false)." + where);
            return;
        }
        String password = get("smtp.password", "SMTP_PASSWORD", "");
        String security = get("smtp.security", "SMTP_SECURITY", "ssl").toLowerCase();
        if (user.isBlank() || (password.isBlank() && !"none".equals(security))) {
            System.out.println("Email: smtp.user and smtp.password are not set, so nothing was sent." + where);
            return;
        }
        try {
            smtp(get("smtp.host", "SMTP_HOST", "smtp.gmail.com"),
                    Integer.parseInt(get("smtp.port", "SMTP_PORT", "465")), security, user, password,
                    from, recipients(to), eml);
            System.out.println("Email sent to " + to + ": " + m.subject());
        } catch (IOException | RuntimeException e) {
            System.out.println("Email not sent (" + e.getMessage() + ")." + where);
        }
    }

    private String get(String key, String env, String def) {
        String v = System.getenv(env);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        return cfg.getProperty(key, def).trim();
    }

    public static List<String> recipients(String to) {
        List<String> out = new ArrayList<>();
        for (String s : to.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                if (t.contains("\r") || t.contains("\n") || !t.contains("@")) {
                    throw new IllegalArgumentException("not an email address: " + t);
                }
                out.add(t);
            }
        }
        return out;
    }

    /** Builds an RFC 5322 message: UTF-8 body in base64, encoded subject, so Chinese text survives. */
    public static String toEml(String from, String to, Message m) {
        String date = ZonedDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.RFC_1123_DATE_TIME);
        String body = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(m.body().replace("\r\n", "\n").replace("\n", "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        return "From: AI Code Verification Gate <" + from + ">\r\n"
                + "To: " + to + "\r\n"
                + "Subject: " + encodeHeader(m.subject()) + "\r\n"
                + "Date: " + date + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n"
                + body + "\r\n";
    }

    public static String encodeHeader(String s) {
        String clean = s.replace("\r", " ").replace("\n", " ");
        boolean ascii = clean.chars().allMatch(c -> c >= 32 && c < 127);
        return ascii ? clean
                : "=?UTF-8?B?" + Base64.getEncoder().encodeToString(clean.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static void smtp(String host, int port, String security, String user, String password, String from,
                             List<String> to, String eml) throws IOException {
        Socket socket;
        if ("ssl".equals(security)) {
            socket = SSLSocketFactory.getDefault().createSocket();
        } else {
            socket = new Socket();
        }
        socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
        socket.setSoTimeout(TIMEOUT_MS);
        try (Socket s0 = socket) {
            Socket s = s0;
            if (s instanceof SSLSocket ssl) {
                ssl.startHandshake();
            }
            Conn c = new Conn(s);
            c.expect(220);
            c.cmd("EHLO aigate.local", 250);
            if ("starttls".equals(security)) {
                c.cmd("STARTTLS", 220);
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(s, host, port, true);
                ssl.startHandshake();
                s = ssl;
                c = new Conn(s);
                c.cmd("EHLO aigate.local", 250);
            }
            if (!password.isBlank()) {
                c.cmd("AUTH LOGIN", 334);
                c.cmd(b64(user), 334);
                c.cmd(b64(password), 235);
            }
            c.cmd("MAIL FROM:<" + from + ">", 250);
            for (String r : to) {
                c.cmd("RCPT TO:<" + r + ">", 250);
            }
            c.cmd("DATA", 354);
            StringBuilder data = new StringBuilder();
            for (String line : eml.split("\r\n", -1)) {
                data.append(line.startsWith(".") ? "." + line : line).append("\r\n");
            }
            c.raw(data + ".\r\n");
            c.expect(250);
            c.cmd("QUIT", 221);
        }
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** One SMTP connection: write a command, read a (possibly multi-line) reply, check its code. */
    private static final class Conn {
        private final BufferedReader in;
        private final OutputStream out;

        Conn(Socket s) throws IOException {
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = s.getOutputStream();
        }

        void cmd(String line, int code) throws IOException {
            raw(line + "\r\n");
            expect(code);
        }

        void raw(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void expect(int code) throws IOException {
            String line;
            String last;
            do {
                line = in.readLine();
                if (line == null) {
                    throw new IOException("the mail server closed the connection");
                }
                last = line;
            } while (line.length() > 3 && line.charAt(3) == '-');
            if (!last.startsWith(String.valueOf(code))) {
                if (last.startsWith("535") || last.startsWith("534")) {
                    throw new IOException("the mail server refused the login; for Gmail use an app password");
                }
                throw new IOException("mail server said: " + last);
            }
        }
    }
}
