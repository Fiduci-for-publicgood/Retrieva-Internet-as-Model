package io.retrieva.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Raw text -> plain declarative sentences. Strips HTML, URLs, formatting, invisible chars and directives. */
public final class Text {
    private Text() {}

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern SCRIPT = Pattern.compile("<(script|style|iframe|object|embed|template|noscript|svg)\\b.*?</\\1\\s*>", F | Pattern.DOTALL);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]{0,2000}>");
    private static final Pattern MDLINK = Pattern.compile("!?\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile(
            "(?:\\b[a-z][a-z0-9+.-]{1,15}://|\\bwww\\.)\\S+"
                    + "|\\b[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"
                    + "|\\b(?:[a-z0-9-]+\\.)+(?:com|org|net|io|gov|edu|co|uk|ai|dev|info|xyz|ru|cn)\\b(?:/\\S*)?", F);
    private static final Pattern FMT = Pattern.compile("[*_`#>|~\\[\\]{}\\\\^=<]+");
    private static final Pattern SPACE = Pattern.compile("[ \\t\\r\\f\\u000B]+");
    private static final Pattern SPLIT = Pattern.compile("(?<=[.!?])\\s+|\\n+|[;:]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final List<Pattern> INJ = Lexicon.INJECTION_PATTERNS.stream().map(p -> Pattern.compile(p, F)).toList();
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);?");
    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""), Map.entry("apos", "'"),
            Map.entry("nbsp", " "), Map.entry("ndash", "–"), Map.entry("mdash", "—"), Map.entry("hellip", "…"),
            Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"), Map.entry("ldquo", "“"), Map.entry("rdquo", "”"),
            Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("trade", "™"), Map.entry("deg", "°"),
            Map.entry("plusmn", "±"), Map.entry("times", "×"), Map.entry("middot", "·"));

    public static final int MAX_CHARS = 200_000;

    /** Common HTML entities and numeric references; unknown named entities are left as-is. */
    static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String g = m.group(1), rep = null;
            if (g.charAt(0) == '#') {
                try {
                    int cp = g.length() > 1 && (g.charAt(1) == 'x' || g.charAt(1) == 'X') ? Integer.parseInt(g.substring(2), 16) : Integer.parseInt(g.substring(1));
                    rep = cp <= 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF) ? "�" : new String(Character.toChars(cp));
                } catch (NumberFormatException e) {
                    rep = "�";
                }
            } else if (m.group().endsWith(";")) {
                rep = NAMED.get(g);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep != null ? rep : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean dropped(int cp) {
        int t = Character.getType(cp);
        return t == Character.CONTROL || t == Character.FORMAT || t == Character.PRIVATE_USE
                || t == Character.SURROGATE || t == Character.UNASSIGNED;
    }

    /** Remove markup, links, formatting and invisible/bidi characters. Idempotent. */
    public static String clean(String raw) {
        String text = (raw.length() > MAX_CHARS ? raw.substring(0, MAX_CHARS) : raw).replace('\t', ' ');
        for (int i = 0; i < 3; i++) {   // unescape can reveal new tags; iterate to a fixpoint
            String prev = text;
            text = TAG.matcher(SCRIPT.matcher(COMMENT.matcher(unescape(text)).replaceAll(" ")).replaceAll(" ")).replaceAll(" ");
            if (text.equals(prev)) break;
        }
        text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().filter(cp -> cp == '\n' || !dropped(cp)).forEach(sb::appendCodePoint);
        text = MDLINK.matcher(sb).replaceAll("$1");
        text = URL.matcher(text).replaceAll(" ");
        text = FMT.matcher(text).replaceAll(" ");
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) lines[i] = SPACE.matcher(lines[i]).replaceAll(" ").strip();
        return String.join("\n", lines);
    }

    public static boolean isInjection(String sentence) {
        String s = sentence.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return false;
        int sp = s.indexOf(' ');
        String first = strip(sp < 0 ? s : s.substring(0, sp), ",.");
        if (Lexicon.IMPERATIVE_START.contains(first) || s.startsWith("please ") || s.startsWith("note:") || s.startsWith("important:")) return true;
        for (Pattern p : INJ) if (p.matcher(s).find()) return true;
        return false;
    }

    private static String strip(String s, String chars) {
        int a = 0, b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) a++;
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) b--;
        return s.substring(a, b);
    }

    public record Sentences(List<String> sentences, int dropped) {}

    /** Safe declarative sentences plus the number dropped as suspected injection. */
    public static Sentences sentences(String raw) {
        List<String> out = new ArrayList<>();
        int dropped = 0;
        for (String part : SPLIT.split(clean(raw))) {
            String s = part.strip();
            if (s.length() < 8 || s.length() > 400) continue;
            if (isInjection(s)) {
                dropped++;
                continue;
            }
            out.add(s);
        }
        return new Sentences(out, dropped);
    }
}
