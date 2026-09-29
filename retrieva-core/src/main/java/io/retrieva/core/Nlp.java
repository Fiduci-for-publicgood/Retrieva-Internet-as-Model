package io.retrieva.core;

import static io.retrieva.core.Lexicon.ADJ;
import static io.retrieva.core.Lexicon.AUX;
import static io.retrieva.core.Lexicon.BREAKS;
import static io.retrieva.core.Lexicon.NEGATORS;
import static io.retrieva.core.Lexicon.STOP;
import static io.retrieva.core.Lexicon.VERBS;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tokens, stems, topic sets, polarity and the baseline left-to-right extractor (the LL shape). */
public final class Nlp {
    private Nlp() {}

    public static final int MAX_SIDE = 6;
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+(?:'[a-z]+)?");
    static final Pattern NO_EVIDENCE = Pattern.compile(
            "^(?:there (?:is|was|are) )?(?:no|little|scant) (?:clear |strong |good |reliable )?evidence (?:that|for|of|to suggest) ");
    private static final Pattern CLAUSE_SPLIT = Pattern.compile("[,()]");

    /** A (subject, predicate, object) triple of bare lowercase word runs. */
    public record Tri(String s, String p, String o) {}

    public static List<String> tokens(String text) {
        String t = text.toLowerCase(Locale.ROOT).replace("won't", "will not").replace("can't", "cannot").replace("n't", " not");
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(t);
        while (m.find()) out.add(m.group());
        return out;
    }

    public static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    /** Topic stems: tokens minus stop words, polarity words and verbs. */
    public static Set<String> content(List<String> toks) {
        Set<String> out = new HashSet<>();
        for (String t : toks) {
            if (t.length() > 1 && !STOP.contains(t) && !AUX.contains(t) && !NEGATORS.contains(t) && !ADJ.containsKey(t)
                    && !VERBS.containsKey(t) && !BREAKS.contains(t)) out.add(stem(t));
        }
        return out;
    }

    public static Set<String> content(String text) {
        return content(tokens(text));
    }

    public static int polarity(String pred, String obj) {
        boolean neg = pred.startsWith("not ");
        int base = Lexicon.LEMMA_POL.getOrDefault(neg ? pred.substring(4) : pred, 0);
        int adj = 0;
        for (String w : obj.split(" ")) {
            Integer a = ADJ.get(w);
            if (a != null) {
                adj = a;
                break;
            }
        }
        int pol = base != 0 && adj != 0 ? base * adj : (base != 0 ? base : adj);
        return neg ? -pol : pol;
    }

    static <T> List<T> last(List<T> l, int n) {
        return new ArrayList<>(l.subList(Math.max(0, l.size() - n), l.size()));
    }

    static <T> List<T> first(List<T> l, int n) {
        return new ArrayList<>(l.subList(0, Math.min(n, l.size())));
    }

    /** Baseline LL parse: leftmost predicate wins, one triple per clause. */
    public static List<Tri> extractLL(String sentence) {
        String s = sentence.toLowerCase(Locale.ROOT).strip();
        boolean negPrefix = false;
        Matcher m = NO_EVIDENCE.matcher(s);
        if (m.find()) {
            negPrefix = true;
            s = s.substring(m.end());
        }
        List<Tri> out = new ArrayList<>();
        for (String clause : CLAUSE_SPLIT.split(s)) {
            List<String> toks = tokens(clause);
            int pi = -1;
            for (int i = 0; i < toks.size(); i++) if (VERBS.containsKey(toks.get(i))) { pi = i; break; }
            if (pi < 0) continue;
            String lemma = VERBS.get(toks.get(pi)).lemma();
            List<String> prefix = toks.subList(0, pi);
            int th = prefix.lastIndexOf("that");
            if (th >= 0) prefix = prefix.subList(th + 1, prefix.size());
            boolean neg = negPrefix;
            for (String t : last(prefix, 3)) if (NEGATORS.contains(t)) neg = true;
            List<String> subj = new ArrayList<>();
            for (String t : prefix) if (!STOP.contains(t) && !AUX.contains(t) && !NEGATORS.contains(t) && !BREAKS.contains(t)) subj.add(t);
            subj = last(subj, 4);
            List<String> rest = toks.subList(pi + 1, toks.size());
            List<String> obj = new ArrayList<>();
            for (int i = 0; i < rest.size(); i++) {
                String t = rest.get(i);
                if (NEGATORS.contains(t) && i < 2) { neg = true; continue; }
                if (BREAKS.contains(t)) break;
                if (AUX.contains(t) || (STOP.contains(t) && !ADJ.containsKey(t))) continue;
                obj.add(t);
                if (obj.size() >= MAX_SIDE) break;
            }
            if (subj.isEmpty() || obj.isEmpty()) continue;
            out.add(new Tri(String.join(" ", subj), (neg ? "not " : "") + lemma, String.join(" ", obj)));
        }
        return out;
    }
}
