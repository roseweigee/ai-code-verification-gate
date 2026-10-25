package dev.aiverify.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer so the gate runs with zero third-party dependencies
 * (useful in locked-down bank environments). Objects become LinkedHashMap,
 * arrays become ArrayList, numbers become Long or Double.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw p.error("Trailing characters");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) v;
    }

    /**
     * Extracts the first JSON object or array found in free text. LLM answers sometimes
     * wrap JSON in prose or a markdown fence; this keeps parsing strict but tolerant of that.
     */
    public static Object parseEmbedded(String text) {
        int start = -1;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '{' || c == '[') {
                start = k;
                break;
            }
        }
        if (start < 0) {
            throw new IllegalArgumentException("No JSON found in model output");
        }
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int end = text.lastIndexOf(close);
        if (end < start) {
            throw new IllegalArgumentException("Unterminated JSON in model output");
        }
        return parse(text.substring(start, end + 1));
    }

    private RuntimeException error(String msg) {
        return new IllegalArgumentException(msg + " at position " + i);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private Object value() {
        if (i >= s.length()) {
            throw error("Unexpected end");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) {
            throw error("Expected " + word);
        }
        i += word.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            if (s.charAt(i) != ':') {
                throw error("Expected ':'");
            }
            i++;
            ws();
            m.put(key, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw error("Expected ',' or '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return list;
        }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("Expected ',' or ']'");
            }
        }
    }

    private String string() {
        if (s.charAt(i) != '"') {
            throw error("Expected string");
        }
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw error("Unterminated string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw error("Bad escape");
                }
            } else {
                b.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String n = s.substring(start, i);
        if (n.isEmpty()) {
            throw error("Unexpected character '" + s.charAt(start) + "'");
        }
        if (n.contains(".") || n.contains("e") || n.contains("E")) {
            return Double.parseDouble(n);
        }
        return Long.parseLong(n);
    }

    // ---------------------------------------------------------------- writer

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 0, true);
        return b.toString();
    }

    public static String writeCompact(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 0, false);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v, int indent, boolean pretty) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String str) {
            quote(b, str);
        } else if (v instanceof Number || v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                newline(b, indent + 1, pretty);
                quote(b, String.valueOf(e.getKey()));
                b.append(pretty ? ": " : ":");
                write(b, e.getValue(), indent + 1, pretty);
            }
            newline(b, indent, pretty);
            b.append('}');
        } else if (v instanceof Iterable<?> it) {
            b.append('[');
            boolean first = true;
            boolean any = false;
            for (Object o : it) {
                any = true;
                if (!first) {
                    b.append(',');
                }
                first = false;
                newline(b, indent + 1, pretty);
                write(b, o, indent + 1, pretty);
            }
            if (any) {
                newline(b, indent, pretty);
            }
            b.append(']');
        } else {
            quote(b, v.toString());
        }
    }

    private static void newline(StringBuilder b, int indent, boolean pretty) {
        if (pretty) {
            b.append('\n').append("  ".repeat(indent));
        }
    }

    private static void quote(StringBuilder b, String str) {
        b.append('"');
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '<' -> b.append("\\u003c"); // safe to embed inside an HTML <script>
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
