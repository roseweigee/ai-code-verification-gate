package dev.aiverify.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small YAML reader for Kubernetes and Cloud Build files (block mappings, sequences,
 * scalars, simple flow lists, multi-document). Enough for policy checks, with no dependency.
 * Not a full YAML implementation: anchors, tags and complex flow styles are not supported.
 */
public final class MiniYaml {

    private record Line(int indent, String text, int number) {
    }

    private final List<Line> lines;
    private int pos;

    private MiniYaml(List<Line> lines) {
        this.lines = lines;
    }

    /** Parses every document in the text ("---" separated). Empty documents are skipped. */
    public static List<Object> parseAll(String text) {
        List<Object> docs = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        String[] raw = text.replace("\r", "").split("\n", -1);
        for (int k = 0; k < raw.length; k++) {
            String l = raw[k];
            if (l.strip().equals("---") || l.startsWith("--- ")) {
                addDoc(docs, current);
                current = new ArrayList<>();
                continue;
            }
            String noComment = stripComment(l);
            if (noComment.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < noComment.length() && noComment.charAt(indent) == ' ') {
                indent++;
            }
            current.add(new Line(indent, noComment.strip(), k + 1));
        }
        addDoc(docs, current);
        return docs;
    }

    private static void addDoc(List<Object> docs, List<Line> lines) {
        if (!lines.isEmpty()) {
            MiniYaml y = new MiniYaml(lines);
            docs.add(y.block(lines.get(0).indent));
        }
    }

    private Object block(int indent) {
        if (pos >= lines.size()) {
            return null;
        }
        return lines.get(pos).text.startsWith("- ") || lines.get(pos).text.equals("-") ? sequence(indent) : mapping(indent);
    }

    private Map<String, Object> mapping(int indent) {
        Map<String, Object> m = new LinkedHashMap<>();
        while (pos < lines.size() && lines.get(pos).indent == indent && !lines.get(pos).text.startsWith("- ")) {
            Line l = lines.get(pos);
            int colon = keyColon(l.text);
            if (colon < 0) {
                pos++;
                continue;
            }
            String key = unquote(l.text.substring(0, colon).strip());
            String rest = l.text.substring(colon + 1).strip();
            pos++;
            if (rest.equals("|") || rest.equals(">") || rest.startsWith("|-") || rest.startsWith(">-")) {
                StringBuilder b = new StringBuilder();
                while (pos < lines.size() && lines.get(pos).indent > indent) {
                    b.append(lines.get(pos).text).append('\n');
                    pos++;
                }
                m.put(key, b.toString());
            } else if (rest.isEmpty()) {
                if (pos < lines.size() && (lines.get(pos).indent > indent
                        || (lines.get(pos).indent == indent && lines.get(pos).text.startsWith("- ")))) {
                    m.put(key, block(lines.get(pos).indent));
                } else {
                    m.put(key, null);
                }
            } else {
                m.put(key, scalar(rest));
            }
        }
        return m;
    }

    private List<Object> sequence(int indent) {
        List<Object> list = new ArrayList<>();
        while (pos < lines.size() && lines.get(pos).indent == indent
                && (lines.get(pos).text.startsWith("- ") || lines.get(pos).text.equals("-"))) {
            Line l = lines.get(pos);
            String item = l.text.equals("-") ? "" : l.text.substring(2).strip();
            if (item.isEmpty()) {
                pos++;
                list.add(pos < lines.size() && lines.get(pos).indent > indent ? block(lines.get(pos).indent) : null);
            } else if (keyColon(item) >= 0 && !item.startsWith("'") && !item.startsWith("\"")) {
                // "- key: value" starts a mapping whose other keys sit at the column of "key"
                int childIndent = indent + 2 + (l.text.length() - 2 - l.text.substring(2).stripLeading().length());
                lines.set(pos, new Line(childIndent, item, l.number));
                list.add(mapping(childIndent));
            } else {
                pos++;
                list.add(scalar(item));
            }
        }
        return list;
    }

    private static Object scalar(String s) {
        if (s.startsWith("[") && s.endsWith("]")) {
            List<Object> items = new ArrayList<>();
            String inner = s.substring(1, s.length() - 1).strip();
            if (!inner.isEmpty()) {
                for (String part : inner.split(",")) {
                    items.add(unquote(part.strip()));
                }
            }
            return items;
        }
        return unquote(s);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Index of the ':' that ends a key (followed by space or end of line), outside quotes. */
    private static int keyColon(String s) {
        boolean sq = false;
        boolean dq = false;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\'' && !dq) {
                sq = !sq;
            } else if (c == '"' && !sq) {
                dq = !dq;
            } else if (c == ':' && !sq && !dq && (k + 1 == s.length() || s.charAt(k + 1) == ' ')) {
                return k;
            }
        }
        return -1;
    }

    private static String stripComment(String l) {
        boolean sq = false;
        boolean dq = false;
        for (int k = 0; k < l.length(); k++) {
            char c = l.charAt(k);
            if (c == '\'' && !dq) {
                sq = !sq;
            } else if (c == '"' && !sq) {
                dq = !dq;
            } else if (c == '#' && !sq && !dq && (k == 0 || l.charAt(k - 1) == ' ')) {
                return l.substring(0, k);
            }
        }
        return l;
    }

    // ------------------------------------------------------------- navigation helpers

    @SuppressWarnings("unchecked")
    public static Object get(Object node, String... path) {
        Object cur = node;
        for (String p : path) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(p);
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object node, String... path) {
        Object v = get(node, path);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    public static String str(Object node, String... path) {
        Object v = get(node, path);
        return v == null ? null : v.toString();
    }
}
