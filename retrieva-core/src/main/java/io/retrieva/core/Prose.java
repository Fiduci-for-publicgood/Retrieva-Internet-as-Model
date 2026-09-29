package io.retrieva.core;

import io.retrieva.core.Agent.Voice;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Realises the prime-numbered crawlers' retrieval points as flowing English. Deterministic and template-based: it
 * only ever reads the triple fields of a {@link Voice} (never source text), fixes verb agreement, attributes each
 * claim to its source with hedging that matches the source's trust, and joins sentences with connectives that follow
 * the stance (for / against / neutral) of what came before.
 *
 * <p>Order is the spoken order: highest crawler number first. Voices from different areas (32 crawler numbers each)
 * form separate paragraphs, so a connective never bridges two unrelated topics.
 */
public final class Prose {
    private Prose() {}

    public static final String EMPTY = "None of the crawlers that speak for this answer retrieved any evidence.";
    private static final int AREA = 32;
    private static final int PER_PARAGRAPH = 5;
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September",
            "October", "November", "December"};
    private static final String[] SAME = {"Likewise", "In addition", "Similarly"};

    public static String realize(List<Voice> voices) {
        if (voices.isEmpty()) return EMPTY;
        List<Voice> ordered = new ArrayList<>(voices);
        ordered.sort((a, b) -> Integer.compare(b.pos(), a.pos()));
        StringBuilder out = new StringBuilder();
        int area = -1, inParagraph = 0, sameRun = 0, index = 0;
        String prev = null;
        for (Voice v : ordered) {
            int a = (v.pos() - 1) / AREA;
            boolean newArea = a != area;
            if (newArea || inParagraph >= PER_PARAGRAPH) {
                if (out.length() > 0) out.append("\n\n");
                inParagraph = 0;
                if (newArea) {
                    prev = null;
                    sameRun = 0;
                }
                area = a;
            } else {
                out.append(' ');
            }
            String connective = prev == null ? null : connective(prev, v.stance(), sameRun);
            sameRun = v.stance().equals(prev) ? sameRun + 1 : 0;
            out.append(sentence(v, connective, index++));
            prev = v.stance();
            inParagraph++;
        }
        return out.toString();
    }

    static String connective(String prev, String cur, int sameRun) {
        if (prev.equals(cur)) return cur.equals("neutral") ? "Also" : SAME[sameRun % SAME.length];
        return switch (cur) {
            case "against" -> "However";
            case "for" -> prev.equals("against") ? "On the other hand" : "More directly";
            default -> "For context";
        };
    }

    /** One sentence for one voice; {@code index} alternates between attribution-first and clause-first shapes. */
    static String sentence(Voice v, String connective, int index) {
        String clause = clause(v);            // lowercase word run, so it can follow a connective as-is
        String when = when(v.month());
        String host = v.host();
        boolean attributionFirst = index % 2 == 0;
        String body;
        if (v.trust() >= 0.55) {
            String verb = v.trust() >= 0.8 ? "confirms" : "reports";
            body = attributionFirst ? "according to " + host + when + ", " + clause : clause + ", " + host + " " + verb + when;
        } else {
            body = attributionFirst ? "a lower-trust source, " + host + when + ", claims that " + clause
                    : clause + ", though that claim comes from " + host + when + ", a lower-trust source";
        }
        return (connective == null ? cap(body) : connective + ", " + body) + ".";
    }

    static String clause(Voice v) {
        boolean plural = plural(v.subject());
        return v.subject() + " " + verb(v.predicate(), plural) + " " + v.object();
    }

    /** Subject-verb agreement for the predicates the lexicon can produce. */
    static String verb(String pred, boolean plural) {
        if (pred.startsWith("not ")) {
            String base = pred.substring(4);
            return switch (base) {
                case "is" -> plural ? "are not" : "is not";
                case "has" -> plural ? "do not have" : "does not have";
                default -> (plural ? "do not " : "does not ") + base;
            };
        }
        return switch (pred) {
            case "is" -> plural ? "are" : "is";
            case "has" -> plural ? "have" : "has";
            default -> plural ? pred : thirdPerson(pred);
        };
    }

    static String thirdPerson(String base) {
        if (base.endsWith("s") || base.endsWith("sh") || base.endsWith("ch") || base.endsWith("x")) return base + "es";
        if (base.endsWith("y") && base.length() > 1 && "aeiou".indexOf(base.charAt(base.length() - 2)) < 0) return base.substring(0, base.length() - 1) + "ies";
        return base + "s";
    }

    static boolean plural(String subject) {
        String[] w = subject.split(" ");
        String last = w[w.length - 1];
        return last.length() > 3 && last.endsWith("s") && !last.endsWith("ss") && !last.endsWith("us") && !last.endsWith("is") && !last.endsWith("ics");
    }

    /** " in September 2025", or "" when the source date is unknown. */
    static String when(String month) {
        if (month == null || month.length() != 7 || month.charAt(4) != '-') return "";
        try {
            int y = Integer.parseInt(month.substring(0, 4)), m = Integer.parseInt(month.substring(5));
            return y < 1990 || m < 1 || m > 12 ? "" : " in " + MONTHS[m - 1] + " " + y;
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
