package dev.aiverify.mutation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Source-level mutation operators (a small subset of what PIT does).
 *
 * Each mutant changes exactly one token. If the test suite still passes against a mutant,
 * the tests are too weak to notice that change: that is how we measure whether
 * AI-written (or human-written) tests actually protect the behavior.
 */
public final class Mutator {

    public record Mutant(int id, String operator, int line, String original, String replacement,
                         String originalLine, String mutatedLine, String source) {
    }

    private record Token(String text, int start, String kind) {
    }

    private static final Map<String, String> BOUNDARY = Map.of(
            "<", "<=", "<=", "<", ">", ">=", ">=", ">");
    private static final Map<String, String> NEGATE = Map.of(
            "==", "!=", "!=", "==", "<", ">=", "<=", ">", ">", "<=", ">=", "<");
    private static final Map<String, String> LOGICAL = Map.of("&&", "||", "||", "&&");
    private static final Map<String, String> MATH = Map.of(
            "+", "-", "-", "+", "*", "/", "/", "*", "%", "*");

    public List<Mutant> mutate(String source) {
        List<Token> tokens = tokenize(source);
        List<Mutant> mutants = new ArrayList<>();
        for (int k = 0; k < tokens.size(); k++) {
            Token t = tokens.get(k);
            if (isDeclarationLine(source, t.start)) {
                continue;
            }
            switch (t.kind) {
                case "op" -> {
                    add(mutants, source, t, BOUNDARY.get(t.text), "Conditional boundary");
                    add(mutants, source, t, NEGATE.get(t.text), "Negate conditional");
                    add(mutants, source, t, LOGICAL.get(t.text), "Swap logical operator");
                    if (MATH.containsKey(t.text) && !isUnary(tokens, k)) {
                        add(mutants, source, t, MATH.get(t.text), "Math operator");
                    }
                    if (t.text.equals("!")) {
                        add(mutants, source, t, "", "Remove negation");
                    }
                }
                case "int" -> {
                    long v = Long.parseLong(t.text.replace("_", "").replace("L", ""));
                    add(mutants, source, t, Long.toString(v + 1), "Change constant");
                }
                case "string" -> add(mutants, source, t, "\"??\"", "Change return code / string");
                case "word" -> {
                    if (t.text.equals("true")) {
                        add(mutants, source, t, "false", "Flip boolean");
                    } else if (t.text.equals("false")) {
                        add(mutants, source, t, "true", "Flip boolean");
                    }
                }
                default -> {
                }
            }
        }
        return mutants;
    }

    private void add(List<Mutant> out, String source, Token t, String replacement, String operator) {
        if (replacement == null) {
            return;
        }
        String mutated = source.substring(0, t.start) + replacement + source.substring(t.start + t.text.length());
        int line = lineOf(source, t.start);
        out.add(new Mutant(out.size() + 1, operator, line, t.text, replacement,
                lineText(source, line).strip(), lineText(mutated, line).strip(), mutated));
    }

    /** A '-' or '+' is unary when it follows an operator, '(' ',' or 'return' (but not ')' or ']'). */
    private static boolean isUnary(List<Token> tokens, int k) {
        String text = tokens.get(k).text;
        if (!text.equals("-") && !text.equals("+")) {
            return false;
        }
        if (k == 0) {
            return true;
        }
        Token prev = tokens.get(k - 1);
        boolean closing = prev.text.equals(")") || prev.text.equals("]");
        return prev.kind.equals("op") || (prev.kind.equals("punct") && !closing) || prev.text.equals("return");
    }

    private static boolean isDeclarationLine(String source, int pos) {
        String l = lineText(source, lineOf(source, pos)).strip();
        return l.startsWith("package ") || l.startsWith("import ") || l.startsWith("@");
    }

    static int lineOf(String source, int pos) {
        int line = 1;
        for (int k = 0; k < pos; k++) {
            if (source.charAt(k) == '\n') {
                line++;
            }
        }
        return line;
    }

    static String lineText(String source, int line) {
        String[] lines = source.split("\n", -1);
        return line - 1 < lines.length ? lines[line - 1] : "";
    }

    /** A small Java lexer: enough to skip comments and literals and to find operators safely. */
    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (s.startsWith("//", i)) {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                i = end < 0 ? n : end + 3; // text blocks are not mutated
            } else if (c == '"' || c == '\'') {
                int start = i++;
                while (i < n && s.charAt(i) != c) {
                    if (s.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
                if (c == '"') {
                    out.add(new Token(s.substring(start, i), start, "string"));
                }
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) {
                    i++;
                }
                String num = s.substring(start, i);
                boolean plainInt = num.matches("[0-9_]+L?");
                out.add(new Token(num, start, plainInt ? "int" : "number"));
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < n && Character.isJavaIdentifierPart(s.charAt(i))) {
                    i++;
                }
                out.add(new Token(s.substring(start, i), start, "word"));
            } else {
                String[] ops = {">>>=", "<<=", ">>=", ">>>", "->", "::", "==", "!=", "<=", ">=", "&&", "||",
                        "++", "--", "+=", "-=", "*=", "/=", "%=", "<<", ">>",
                        "<", ">", "!", "+", "-", "*", "/", "%", "=", "?", ":", "&", "|", "^", "~"};
                String found = null;
                for (String op : ops) {
                    if (s.startsWith(op, i)) {
                        found = op;
                        break;
                    }
                }
                if (found != null) {
                    out.add(new Token(found, i, "op"));
                    i += found.length();
                } else {
                    out.add(new Token(String.valueOf(c), i, "punct"));
                    i++;
                }
            }
        }
        return out;
    }
}
