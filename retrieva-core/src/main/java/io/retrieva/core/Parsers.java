package io.retrieva.core;

import static io.retrieva.core.Lexicon.ADJ;
import static io.retrieva.core.Lexicon.AUX;
import static io.retrieva.core.Lexicon.BREAKS;
import static io.retrieva.core.Lexicon.NEGATORS;
import static io.retrieva.core.Lexicon.STOP;
import static io.retrieva.core.Lexicon.VERBS;
import static io.retrieva.core.Nlp.MAX_SIDE;
import static io.retrieva.core.Nlp.content;
import static io.retrieva.core.Nlp.first;
import static io.retrieva.core.Nlp.last;
import static io.retrieva.core.Nlp.polarity;
import static io.retrieva.core.Nlp.tokens;

import io.retrieva.core.Nlp.Tri;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nine parse shapes over one sentence plus a voting ensemble.
 *
 * <pre>
 *  ll       left-to-right, leftmost verb wins
 *  llr      LL + right-context lookahead: skips noun-ish verbs ("the increase in sales reduces ...")
 *  lr       shift-reduce (bottom-up): NP V NP -> S, coordinated VPs share a subject, relative clauses
 *  rl       backwards: rightmost verb first; elliptical verbs inherit the subject to their left
 *  qar      question -> claim ("Does X improve Y?", "Is X safe?") and question+answer pairs
 *  passive  "Y is improved by X" -> (X, improve, Y)
 *  pattern  phrase table: "leads to", "is good for", "is associated with"
 *  center   the verb nearest the middle of the clause
 *  head     head-noun triples: nearest content word each side of each verb
 * </pre>
 */
public final class Parsers {
    private Parsers() {}

    public static final List<String> PRIORITY = List.of("ll", "lr", "llr", "rl", "passive", "pattern", "qar", "center", "head");
    static final Map<String, Function<String, List<Tri>>> PARSERS = new LinkedHashMap<>();

    static {
        PARSERS.put("ll", Nlp::extractLL);
        PARSERS.put("lr", Parsers::lr);
        PARSERS.put("llr", Parsers::llr);
        PARSERS.put("rl", Parsers::rl);
        PARSERS.put("passive", Parsers::passive);
        PARSERS.put("pattern", Parsers::pattern);
        PARSERS.put("qar", Parsers::qar);
        PARSERS.put("center", Parsers::center);
        PARSERS.put("head", Parsers::head);
    }

    private static final Set<String> DET = Set.of("the", "a", "an", "this", "that", "these", "those", "its", "their", "his", "her",
            "our", "your", "my", "any", "some", "many", "most", "all", "more", "less", "no");
    private static final Set<String> NOUNISH = Set.of("increase", "decrease", "use", "cause", "benefit", "harm", "damage", "aid",
            "support", "delay", "help", "influence", "produce");
    private static final Set<String> NOUNISH_FORMS = new HashSet<>();
    static {
        VERBS.forEach((f, v) -> { if (NOUNISH.contains(v.lemma())) NOUNISH_FORMS.add(f); });
    }
    private static final Set<String> NOUN_FOLLOW = Set.of("in", "of", "from");
    private static final Set<String> COPULA = Set.of("is", "are", "was", "were", "be", "been", "being");
    private static final Set<String> QAUX = Set.of("does", "do", "did", "is", "are", "was", "were", "can", "could", "will", "would",
            "should", "has", "have", "had", "may", "might");
    private static final Set<String> OPEN_Q = Set.of("what", "who", "whom", "which", "when", "where", "whose");
    private static final Set<String> RELATIVE = Set.of("which", "that", "who");

    private record Phrase(List<String> words, String lemma) {}

    private static Phrase ph(String lemma, String... w) {
        return new Phrase(List.of(w), lemma);
    }

    private static final List<Phrase> PHRASES = List.of(
            ph("cause", "leads", "to"), ph("cause", "lead", "to"), ph("cause", "led", "to"),
            ph("cause", "results", "in"), ph("cause", "result", "in"), ph("cause", "resulted", "in"),
            ph("cause", "contributes", "to"), ph("cause", "contribute", "to"),
            ph("improve", "is", "good", "for"), ph("improve", "are", "good", "for"),
            ph("harm", "is", "bad", "for"), ph("harm", "are", "bad", "for"),
            ph("support", "is", "essential", "for"), ph("associate", "is", "linked", "to"),
            ph("associate", "is", "associated", "with"), ph("associate", "are", "associated", "with"));

    // -- shared machinery ------------------------------------------------------------------------
    private record Prep(boolean neg, List<List<String>> clauses) {}

    private static Prep prep(String sentence) {
        String s = sentence.toLowerCase(Locale.ROOT).strip();
        while (s.endsWith("?")) s = s.substring(0, s.length() - 1);
        boolean neg = false;
        Matcher m = Nlp.NO_EVIDENCE.matcher(s);
        if (m.find()) {
            neg = true;
            s = s.substring(m.end());
        }
        List<List<String>> clauses = new ArrayList<>();
        for (String c : s.split("[,()]", -1)) {
            List<String> t = tokens(c);
            if (!t.isEmpty()) clauses.add(t);
        }
        return new Prep(neg, clauses);
    }

    /** Noun phrase ending at the right edge of toks (after the last break). */
    static List<String> npLeft(List<String> toks) {
        int th = toks.lastIndexOf("that");
        if (th >= 0) toks = toks.subList(th + 1, toks.size());
        for (int i = toks.size() - 1; i >= 0; i--) {
            if (BREAKS.contains(toks.get(i))) {
                toks = toks.subList(i + 1, toks.size());
                break;
            }
        }
        List<String> out = new ArrayList<>();
        for (String t : toks) if (!STOP.contains(t) && !AUX.contains(t) && !NEGATORS.contains(t) && !BREAKS.contains(t)) out.add(t);
        return last(out, 4);
    }

    private record NpR(List<String> toks, boolean neg) {}

    /** Noun phrase starting at the left edge of toks; also reports a leading negation. */
    private static NpR npRight(List<String> toks) {
        boolean neg = false;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            if (NEGATORS.contains(t) && i < 2) { neg = true; continue; }
            if (BREAKS.contains(t)) break;
            if (AUX.contains(t) || (STOP.contains(t) && !ADJ.containsKey(t))) continue;
            out.add(t);
            if (out.size() >= MAX_SIDE) break;
        }
        return new NpR(out, neg);
    }

    private static Tri mk(List<String> subj, String lemma, boolean neg, List<String> obj) {
        if (subj.isEmpty() || obj.isEmpty()) return null;
        return new Tri(String.join(" ", subj), (neg ? "not " : "") + lemma, String.join(" ", obj));
    }

    private static boolean hasNeg(List<String> toks) {
        for (String t : toks) if (NEGATORS.contains(t)) return true;
        return false;
    }

    private static List<String> slice(List<String> l, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(l.size(), to);
        return from >= to ? List.of() : l.subList(from, to);
    }

    private static Tri tripleAt(List<String> toks, int vi, boolean negPrefix) {
        String lemma = VERBS.get(toks.get(vi)).lemma();
        NpR o = npRight(toks.subList(vi + 1, toks.size()));
        return mk(npLeft(toks.subList(0, vi)), lemma, negPrefix || hasNeg(slice(toks, vi - 3, vi)) || o.neg, o.toks);
    }

    private static List<Integer> verbs(List<String> toks) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) if (VERBS.containsKey(toks.get(i))) out.add(i);
        return out;
    }

    private static void add(List<Tri> out, Tri t) {
        if (t != null) out.add(t);
    }

    // -- the parsers -----------------------------------------------------------------------------
    static List<Tri> llr(String sentence) {
        Prep p = prep(sentence);
        List<Tri> out = new ArrayList<>();
        for (List<String> toks : p.clauses) {
            for (int vi : verbs(toks)) {
                boolean nounUse = NOUNISH_FORMS.contains(toks.get(vi))
                        && ((vi > 0 && DET.contains(toks.get(vi - 1))) || (vi + 1 < toks.size() && NOUN_FOLLOW.contains(toks.get(vi + 1))));
                if (nounUse) continue;
                add(out, tripleAt(toks, vi, p.neg));
                break;
            }
        }
        return out;
    }

    private static final class Sym {
        final String sym;
        List<String> toks;
        boolean neg;

        Sym(String sym, List<String> toks, boolean neg) {
            this.sym = sym;
            this.toks = toks;
            this.neg = neg;
        }
    }

    private static void reduceNp(List<Sym> stack) {
        while (stack.size() >= 2 && stack.get(stack.size() - 1).sym.equals("NP") && stack.get(stack.size() - 2).sym.equals("NP")) {
            Sym b = stack.remove(stack.size() - 1);
            Sym a = stack.get(stack.size() - 1);
            List<String> merged = new ArrayList<>(a.toks);
            merged.addAll(b.toks);
            a.toks = merged;
        }
    }

    private static boolean tail(List<Sym> stack, String... syms) {
        if (stack.size() < syms.length) return false;
        for (int i = 0; i < syms.length; i++) if (!stack.get(stack.size() - syms.length + i).sym.equals(syms[i])) return false;
        return true;
    }

    /**
     * Shift-reduce. Stack symbols NP, V (with negation), S. Rules after every shift:
     * [AUX|NEG] V -> V; V NEG -> V(neg); (DET|N)+ -> NP; NP V NP -> S; S BRK V NP -> S S' (coordinated VP).
     * A clause opening with which/that/who, or with no subject, takes the previous clause's subject.
     */
    static List<Tri> lr(String sentence) {
        Prep p = prep(sentence);
        List<Tri> out = new ArrayList<>();
        List<String> carry = null;
        for (List<String> toks : p.clauses) {
            List<Sym> stack = new ArrayList<>();
            List<String> subjOfClause = null;
            boolean pending = carry != null && RELATIVE.contains(toks.get(0));
            for (String t : toks) {
                if (VERBS.containsKey(t)) {
                    reduceNp(stack);
                    if (!stack.isEmpty() && stack.get(stack.size() - 1).sym.equals("NEGAUX")) {
                        stack.remove(stack.size() - 1);
                        stack.add(new Sym("V", List.of(t), true));
                    } else {
                        stack.add(new Sym("V", List.of(t), false));
                    }
                } else if (NEGATORS.contains(t)) {
                    if (!stack.isEmpty() && stack.get(stack.size() - 1).sym.equals("V")) stack.get(stack.size() - 1).neg = true;
                    else stack.add(new Sym("NEGAUX", List.of(t), true));
                } else if (AUX.contains(t) || DET.contains(t) || STOP.contains(t)) {
                    if (ADJ.containsKey(t)) {
                        stack.add(new Sym("NP", List.of(t), false));
                        reduceNp(stack);
                    }
                    continue;
                } else if (BREAKS.contains(t)) {
                    stack.add(new Sym("BRK", List.of(t), false));
                } else {
                    stack.add(new Sym("NP", List.of(t), false));
                    reduceNp(stack);
                }
                if (tail(stack, "NP", "V", "NP")) {
                    int n = stack.size();
                    Sym s = stack.get(n - 3), v = stack.get(n - 2), o = stack.get(n - 1);
                    stack.subList(n - 3, n).clear();
                    Tri tr = mk(last(s.toks, 4), VERBS.get(v.toks.get(0)).lemma(), p.neg || v.neg, first(o.toks, MAX_SIDE));
                    if (tr != null) {
                        out.add(tr);
                        subjOfClause = last(s.toks, 4);
                    }
                    stack.add(new Sym("S", s.toks, false));
                } else if (tail(stack, "S", "BRK", "V", "NP")) {
                    int n = stack.size();
                    Sym s = stack.get(n - 4), v = stack.get(n - 2), o = stack.get(n - 1);
                    stack.subList(n - 4, n).clear();
                    add(out, mk(last(s.toks, 4), VERBS.get(v.toks.get(0)).lemma(), p.neg || v.neg, first(o.toks, MAX_SIDE)));
                    stack.add(new Sym("S", s.toks, false));
                }
            }
            boolean headless = !stack.isEmpty() && (stack.get(0).sym.equals("BRK") || stack.get(0).sym.equals("V"));
            if (subjOfClause == null && carry != null && (pending || headless)) {
                Sym v = null, np = null;
                for (Sym x : stack) {
                    if (v == null && x.sym.equals("V")) v = x;
                    if (x.sym.equals("NP")) np = x;
                }
                if (v != null && np != null) {
                    Tri tr = mk(carry, VERBS.get(v.toks.get(0)).lemma(), p.neg || v.neg, first(np.toks, MAX_SIDE));
                    if (tr != null) {
                        out.add(tr);
                        subjOfClause = carry;
                    }
                }
            }
            boolean anyV = stack.stream().anyMatch(x -> x.sym.equals("V"));
            if (!anyV && !toks.isEmpty()) {
                List<String> np = npLeft(toks);
                carry = np.isEmpty() ? carry : np;      // a bare NP clause becomes the topic
            } else {
                carry = subjOfClause != null ? subjOfClause : carry;
            }
        }
        return out;
    }

    private record Row(int vi, List<String> s, List<String> o, boolean neg) {}

    /** Backwards: verbs resolved right to left; a verb with no subject of its own inherits the one on its left. */
    static List<Tri> rl(String sentence) {
        Prep p = prep(sentence);
        List<Tri> out = new ArrayList<>();
        for (List<String> toks : p.clauses) {
            List<Integer> vis = verbs(toks);
            List<Row> rows = new ArrayList<>();
            for (int k = vis.size() - 1; k >= 0; k--) {
                int vi = vis.get(k);
                List<String> left = toks.subList(k > 0 ? vis.get(k - 1) + 1 : 0, vi);
                int cut = -1;
                for (int i = 0; i < left.size(); i++) if (BREAKS.contains(left.get(i))) cut = i;
                List<String> s = npLeft(left.subList(cut + 1, left.size()));
                int end = k + 1 < vis.size() ? vis.get(k + 1) : toks.size();
                NpR o = npRight(end > vi + 1 ? toks.subList(vi + 1, end) : List.of());
                rows.add(new Row(vi, s, o.toks, p.neg || o.neg || hasNeg(slice(toks, vi - 3, vi))));
            }
            Collections.reverse(rows);
            List<String> lastS = null;
            for (Row r : rows) {
                List<String> s = r.s.isEmpty() ? lastS : r.s;
                if (s != null && !s.isEmpty()) {
                    lastS = s;
                    add(out, mk(s, VERBS.get(toks.get(r.vi)).lemma(), r.neg, r.o));
                }
            }
        }
        return out;
    }

    /** Question -> claim. Polar and why/how questions only; who/what/which have an unknown subject. */
    static List<Tri> qar(String sentence) {
        String s = sentence.toLowerCase(Locale.ROOT).strip();
        List<String> toks = tokens(s);
        if (toks.isEmpty() || !(s.endsWith("?") || QAUX.contains(toks.get(0)) || OPEN_Q.contains(toks.get(0))
                || toks.get(0).equals("why") || toks.get(0).equals("how"))) return List.of();
        if ((toks.get(0).equals("why") || toks.get(0).equals("how")) && toks.size() > 2 && QAUX.contains(toks.get(1))) toks = toks.subList(1, toks.size());
        if (OPEN_Q.contains(toks.get(0)) || !QAUX.contains(toks.get(0))) return List.of();
        String aux = toks.get(0);
        List<String> rest = toks.subList(1, toks.size());
        if (COPULA.contains(aux)) {                                   // "is coffee safe" -> coffee is safe
            int ai = -1;
            for (int i = 0; i < rest.size(); i++) if (ADJ.containsKey(rest.get(i))) { ai = i; break; }
            if (ai < 0) return List.of();
            NpR o = npRight(rest.subList(ai, rest.size()));
            Tri t = mk(npLeft(rest.subList(0, ai)), "is", o.neg || hasNeg(rest.subList(0, ai)), o.toks);
            return t == null ? List.of() : List.of(t);
        }
        int vi = -1;                                                  // "does coffee improve memory"
        for (int i = 0; i < rest.size(); i++) if (VERBS.containsKey(rest.get(i))) { vi = i; break; }
        if (vi < 0) return List.of();
        Tri t = tripleAt(rest, vi, false);
        return t == null ? List.of() : List.of(t);
    }

    /** 'Memory is not improved by coffee' -> (coffee, not improve, memory). */
    static List<Tri> passive(String sentence) {
        Prep p = prep(sentence);
        List<Tri> out = new ArrayList<>();
        for (List<String> toks : p.clauses) {
            for (int j = 0; j < toks.size(); j++) {
                if (!toks.get(j).equals("by") || j < 2 || !VERBS.containsKey(toks.get(j - 1))) continue;
                String past = toks.get(j - 1);
                if (!past.endsWith("ed") && !past.endsWith("d")) continue;
                int ci = -1;
                for (int i = j - 2; i >= 0; i--) if (COPULA.contains(toks.get(i))) { ci = i; break; }
                if (ci < 0) continue;
                NpR subj = npRight(toks.subList(j + 1, toks.size()));
                add(out, mk(first(subj.toks, 4), VERBS.get(past).lemma(), p.neg || hasNeg(toks.subList(ci, j)),
                        last(npLeft(toks.subList(0, ci)), MAX_SIDE)));
            }
        }
        return out;
    }

    static List<Tri> pattern(String sentence) {
        Prep p = prep(sentence);
        List<Tri> out = new ArrayList<>();
        for (List<String> toks : p.clauses) {
            for (Phrase ph : PHRASES) {
                int n = ph.words.size();
                for (int i = 0; i + n <= toks.size(); i++) {
                    if (toks.subList(i, i + n).equals(ph.words)) {
                        NpR o = npRight(toks.subList(i + n, toks.size()));
                        add(out, mk(npLeft(toks.subList(0, i)), ph.lemma, p.neg || o.neg || hasNeg(slice(toks, i - 2, i)), o.toks));
                    }
                }
            }
        }
        return out;
    }

    static List<Tri> center(String sentence) {
        Prep p = prep(sentence);
        List<Tri> out = new ArrayList<>();
        for (List<String> toks : p.clauses) {
            List<Integer> vis = verbs(toks);
            if (vis.isEmpty()) continue;
            int best = vis.get(0);
            for (int i : vis) if (Math.abs(i - toks.size() / 2.0) < Math.abs(best - toks.size() / 2.0)) best = i;
            add(out, tripleAt(toks, best, p.neg));
        }
        return out;
    }

    /** Head words only: the nearest content word on each side of every verb. */
    static List<Tri> head(String sentence) {
        Prep p = prep(sentence);
        List<Tri> out = new ArrayList<>();
        for (List<String> toks : p.clauses) {
            for (int vi : verbs(toks)) {
                List<String> seg = toks.subList(0, vi);
                int cut = -1;
                for (int i = 0; i < seg.size(); i++) if (BREAKS.contains(seg.get(i))) cut = i;
                List<String> left = new ArrayList<>();
                for (String t : seg.subList(cut + 1, seg.size())) {
                    if (!STOP.contains(t) && !AUX.contains(t) && !NEGATORS.contains(t) && !VERBS.containsKey(t)) left.add(t);
                }
                NpR right = npRight(toks.subList(vi + 1, toks.size()));
                add(out, mk(last(left, 1), VERBS.get(toks.get(vi)).lemma(), p.neg || right.neg || hasNeg(slice(toks, vi - 3, vi)),
                        first(right.toks, 1)));
            }
        }
        return out;
    }

    // -- question + answer pairs -----------------------------------------------------------------
    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern QA = Pattern.compile("([^.?!\\n]{6,200}\\?)\\s*(?:a:|answer:)?\\s*(yes|no|not really|probably not)\\b", F);
    private static final Pattern Q_PREFIX = Pattern.compile("^\\s*q:\\s*", F);

    private static Tri flip(Tri t) {
        return new Tri(t.s(), t.p().startsWith("not ") ? t.p().substring(4) : "not " + t.p(), t.o());
    }

    /** 'Does coffee improve memory? No.' -> (coffee, not improve, memory). Input must already be cleaned. */
    public static List<Tri> qaTriples(String cleanedText) {
        List<Tri> out = new ArrayList<>();
        Matcher m = QA.matcher(cleanedText);
        while (m.find()) {
            String q = Q_PREFIX.matcher(m.group(1).strip()).replaceFirst("");
            if (Text.isInjection(q)) continue;
            String a = m.group(2).toLowerCase(Locale.ROOT);
            boolean neg = a.startsWith("no") || a.startsWith("not") || a.startsWith("probably not");
            for (Tri t : qar(q)) out.add(neg ? flip(t) : t);
        }
        return out;
    }

    // -- ensemble --------------------------------------------------------------------------------
    /** Every parser's raw reading, in priority order, for inspection. */
    public static Map<String, List<Tri>> parseReport(String sentence) {
        Map<String, List<Tri>> rep = new LinkedHashMap<>();
        for (String name : PRIORITY) {
            List<Tri> r;
            try {
                r = PARSERS.get(name).apply(sentence);
            } catch (RuntimeException e) {
                r = List.of();
            }
            rep.put(name, new ArrayList<>(r));
        }
        return rep;
    }

    private static boolean sameTopic(Set<String> ss, Set<String> os, Set<String> cs, Set<String> co) {
        return intersects(ss, cs) && (intersects(os, co) || (os.isEmpty() && co.isEmpty()));
    }

    static boolean intersects(Set<String> a, Set<String> b) {
        for (String x : a) if (b.contains(x)) return true;
        return false;
    }

    private static final class Cluster {
        Tri t;
        Set<String> ss, os;
        Set<String> votes = new LinkedHashSet<>();
        int i;
    }

    private static final int CACHE = 100_000;
    private static final Map<String, List<Tri>> CACHE_MAP = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Tri>> e) {
            return size() > CACHE;
        }
    });

    /** Best-voted readings of a sentence: clustered across parsers, contradicted-by-majority readings dropped. */
    public static List<Tri> extractAll(String sentence) {
        List<Tri> hit = CACHE_MAP.get(sentence);
        if (hit != null) return hit;
        List<Tri> res = ensemble(sentence);
        CACHE_MAP.put(sentence, res);
        return res;
    }

    private static List<Tri> ensemble(String sentence) {
        Map<String, List<Tri>> rep = parseReport(sentence);
        List<Tri> pas = rep.get("passive");
        if (!pas.isEmpty()) {      // a passive clause outranks readings that swap its roles or use 'is'
            for (String name : PRIORITY) {
                if (name.equals("passive")) continue;
                List<Tri> keep = new ArrayList<>();
                for (Tri t : rep.get(name)) {
                    Set<String> cs = content(t.s()), co = content(t.o());
                    boolean swapped = false, copular = false;
                    for (Tri x : pas) {
                        if (intersects(cs, content(x.o())) && intersects(co, content(x.s()))) swapped = true;
                        if ((t.p().equals("is") || t.p().equals("not is")) && intersects(cs, content(x.o()))) copular = true;
                    }
                    if (!swapped && !copular) keep.add(t);
                }
                rep.put(name, keep);
            }
        }
        List<Cluster> clusters = new ArrayList<>();
        for (Map.Entry<String, List<Tri>> e : rep.entrySet()) {
            for (Tri t : e.getValue()) {
                Set<String> ss = content(t.s()), os = content(t.o());
                Cluster found = null;
                for (Cluster c : clusters) {
                    if (c.t.p().equals(t.p()) && intersects(ss, c.ss)
                            && (intersects(os, c.os) || (os.isEmpty() && c.os.isEmpty() && t.o().equals(c.t.o())))) {
                        found = c;
                        break;
                    }
                }
                if (found != null) {
                    found.votes.add(e.getKey());
                } else {
                    Cluster c = new Cluster();
                    c.t = t;
                    c.ss = ss;
                    c.os = os;
                    c.votes.add(e.getKey());
                    c.i = clusters.size();
                    clusters.add(c);
                }
            }
        }
        List<Cluster> keep = new ArrayList<>();
        for (Cluster c : clusters) {
            int pol = polarity(c.t.p(), c.t.o());
            boolean ok = true;
            for (Cluster d : clusters) {
                if (d != c && sameTopic(d.ss, d.os, c.ss, c.os) && -pol != 0 && polarity(d.t.p(), d.t.o()) == -pol && c.votes.size() <= d.votes.size()) {
                    ok = false;
                    break;
                }
            }
            if (ok) keep.add(c);
        }
        keep.sort((a, b) -> a.votes.size() != b.votes.size() ? Integer.compare(b.votes.size(), a.votes.size()) : Integer.compare(a.i, b.i));
        List<Tri> out = new ArrayList<>();
        for (Cluster c : keep) out.add(c.t);
        return List.copyOf(out);
    }

    /** Diagnostics: each surviving reading with the parsers that agree on it. */
    public static Map<Tri, List<String>> votes(String sentence) {
        Map<String, List<Tri>> rep = parseReport(sentence);
        Map<Tri, List<String>> out = new LinkedHashMap<>();
        for (Tri t : extractAll(sentence)) {
            Set<String> ss = content(t.s()), os = content(t.o());
            List<String> who = new ArrayList<>();
            for (Map.Entry<String, List<Tri>> e : rep.entrySet()) {
                for (Tri x : e.getValue()) {
                    if (x.p().equals(t.p()) && intersects(content(x.s()), ss)
                            && (intersects(content(x.o()), os) || (os.isEmpty() && x.o().equals(t.o())))) {
                        who.add(e.getKey());
                        break;
                    }
                }
            }
            out.put(t, who);
        }
        return out;
    }
}
