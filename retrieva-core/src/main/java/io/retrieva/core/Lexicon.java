package io.retrieva.core;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Predicate lemmas with polarity, evaluative words and stop lists. Extend these tables to widen coverage. */
public final class Lexicon {
    private Lexicon() {}

    public record Verb(String lemma, int pol) {}

    static Set<String> forms(String base) {
        Set<String> out = new LinkedHashSet<>();
        out.add(base);
        out.add(base.endsWith("s") || base.endsWith("sh") || base.endsWith("ch") || base.endsWith("x") ? base + "es" : base + "s");
        out.add(base.endsWith("e") ? base + "d" : base + "ed");
        if (base.endsWith("y") && "aeiou".indexOf(base.charAt(base.length() - 2)) < 0) {
            out.add(base.substring(0, base.length() - 1) + "ies");
            out.add(base.substring(0, base.length() - 1) + "ied");
        }
        return out;
    }

    private static final List<String> POS = List.of("improve", "increase", "raise", "boost", "enhance", "promote", "support",
            "strengthen", "help", "protect", "benefit", "aid", "enable", "accelerate", "stimulate", "reinforce", "confirm");
    private static final List<String> NEG = List.of("reduce", "decrease", "lower", "harm", "worsen", "impair", "weaken", "inhibit",
            "prevent", "damage", "undermine", "hinder", "diminish", "suppress", "disrupt", "contradict", "refute", "delay");
    private static final List<String> NEUTRAL = List.of("cause", "contain", "include", "affect", "influence", "involve", "produce",
            "require", "use");

    /** surface form -> (lemma, polarity) */
    public static final Map<String, Verb> VERBS = new LinkedHashMap<>();
    /** lemma -> polarity */
    public static final Map<String, Integer> LEMMA_POL = new LinkedHashMap<>();

    static {
        for (String l : POS) for (String f : forms(l)) VERBS.put(f, new Verb(l, 1));
        for (String l : NEG) for (String f : forms(l)) VERBS.put(f, new Verb(l, -1));
        for (String l : NEUTRAL) for (String f : forms(l)) VERBS.put(f, new Verb(l, 0));
        for (String f : List.of("is", "are", "was", "were", "be", "been", "being")) VERBS.put(f, new Verb("is", 0));
        for (String f : List.of("has", "have", "had")) VERBS.put(f, new Verb("has", 0));
        for (Verb v : VERBS.values()) LEMMA_POL.put(v.lemma(), v.pol());
    }

    public static final Map<String, Integer> ADJ = new LinkedHashMap<>();
    static {
        for (String w : "safe effective beneficial healthy true accurate reliable helpful useful good positive secure legitimate valid proven".split(" ")) ADJ.put(w, 1);
        for (String w : ("unsafe dangerous ineffective harmful unhealthy false inaccurate unreliable useless bad negative insecure "
                + "illegitimate invalid harm damage toxic risky").split(" ")) ADJ.put(w, -1);
    }

    public static final Set<String> NEGATORS = Set.of("not", "no", "never", "without", "cannot", "neither", "nor");
    public static final Set<String> AUX = Set.of("does", "do", "did", "can", "may", "might", "will", "could", "would", "should", "must",
            "also", "often", "really", "regularly", "generally", "typically", "still", "always", "actually", "significantly", "greatly",
            "slightly", "reportedly", "widely");
    public static final Set<String> STOP = Set.of("the", "a", "an", "this", "that", "these", "those", "some", "many", "most", "any", "all",
            "of", "to", "in", "on", "at", "by", "for", "with", "from", "as", "it", "its", "their", "his", "her", "our", "your", "my",
            "there", "here", "then", "than", "so", "such", "very", "more", "less", "much", "why", "how", "recent", "new", "studies",
            "study", "research", "evidence", "found", "shows", "show", "showed", "suggest", "suggests", "reports", "report");
    public static final Set<String> BREAKS = Set.of("and", "but", "because", "while", "which", "although", "when", "or", "whereas",
            "since", "though", "however", "if", "unless", "whether");

    /** Sentence-level prompt-injection patterns: untrusted text must be declarative, never directive. */
    public static final List<String> INJECTION_PATTERNS = List.of(
            "\\b(ignore|disregard|forget|override|bypass)\\b.{0,40}\\b(instruction|prompt|rule|guideline|previous|prior|above|earlier)",
            "\\b(system|developer|assistant|user)\\s*(prompt|message|role)\\b",
            "^\\s*(system|assistant|user|human|ai)\\s*[:>]",
            "\\b(you are|you're|you must|you should|you will|you shall)\\b",
            "\\b(act|behave|respond|reply|answer|pretend|roleplay)\\s+(as|like|with|that|only|in)\\b",
            "\\b(jailbreak|dan mode|developer mode|do anything now|prompt injection)\\b",
            "\\b(reveal|leak|print|output|repeat|disclose|exfiltrate)\\b.{0,30}\\b(prompt|instruction|secret|key|password|token|credential)",
            "\\b(new|updated|additional)\\s+instructions?\\b",
            "\\b(run|execute|eval|call|invoke|download|install|curl|fetch|send|email|post)\\b.{0,30}\\b(command|script|code|function|tool|file|payload|this|the following|to)\\b",
            "\\bdo not (tell|reveal|mention|inform|let)\\b",
            "\\bbase64\\b|\\\\x[0-9a-f]{2}|&#\\d+;|<\\|");
    public static final Set<String> IMPERATIVE_START = Set.of("ignore", "disregard", "forget", "reveal", "print", "output", "respond",
            "answer", "say", "write", "call", "visit", "click", "download", "execute", "run", "send", "act", "pretend", "remember", "tell",
            "give", "show", "list", "translate", "summarize", "repeat", "stop", "start", "begin", "enter", "type", "use", "make");
}
