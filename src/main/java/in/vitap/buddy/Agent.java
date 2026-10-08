package in.vitap.buddy;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.TextStyle;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "brain": retrieve -> (optionally) generate -> score confidence -> learn from gaps.
 */
public class Agent {
    public record Reply(String answer, double confidence, List<KnowledgeBase.Doc> sources,
                        List<String> followUps, boolean usedLlm, boolean gap) {}

    private static final double MIN_CONFIDENCE = 0.34;
    private static final String SYSTEM = """
            You are "VIT-AP Buddy", a friendly senior-student style assistant that helps first-year students.
            Answer ONLY from the CONTEXT provided. Never invent facts, dates, amounts or phone numbers.
            If the context does not fully answer, say so honestly and point to the right office if the context names one.
            Keep it short, warm and simple. Use short bullet points for steps.
            Reply in the same language the student used (English, Telugu, Hindi, etc.).""";

    private final KnowledgeBase kb;
    private final LlmClient llm;
    private final Map<String, Integer> gaps = new ConcurrentHashMap<>();
    private final Path gapFile = Paths.get("unanswered-questions.log");

    public Agent(KnowledgeBase kb, LlmClient llm) { this.kb = kb; this.llm = llm; }

    public Reply ask(String question, String profile) {
        String q = question.trim();
        String searchQ = q;

        // Multilingual novelty: if the question isn't Latin script, translate it for retrieval.
        if (llm.enabled() && q.chars().anyMatch(c -> c > 0x024F)) {
            try {
                searchQ = llm.complete("Translate the user's text to plain English. Output only the translation.", q);
            } catch (Exception ignored) { }
        }

        List<KnowledgeBase.Hit> hits = kb.search(resolveDays(searchQ), profile, 3);
        if (hits.isEmpty() || hits.get(0).coverage() < MIN_CONFIDENCE) {
            logGap(q);
            return new Reply("I'm not sure about that one yet \u2014 I only answer from verified university info, "
                    + "so I won't guess. I've logged your question so the admins can add it. "
                    + "Meanwhile, try asking a senior or the student help desk.",
                    hits.isEmpty() ? 0 : hits.get(0).coverage(), List.of(), suggestions(null), false, true);
        }

        KnowledgeBase.Hit top = hits.get(0);
        List<KnowledgeBase.Doc> sources = hits.stream().filter(h -> h.coverage() >= MIN_CONFIDENCE && h.score() >= 0.6 * top.score()).map(KnowledgeBase.Hit::doc).limit(2).toList();

        String answer; boolean usedLlm = false;
        if (llm.enabled()) {
            try {
                StringBuilder ctx = new StringBuilder();
                for (var d : sources) ctx.append("- ").append(d.title()).append(": ").append(d.answer()).append("\n");
                answer = llm.complete(SYSTEM, "CONTEXT:\n" + ctx + "\nSTUDENT PROFILE: " + profile + "\nQUESTION: " + q);
                usedLlm = true;
            } catch (Exception e) {
                answer = top.doc().answer();
            }
        } else {
            answer = top.doc().answer();
        }
        return new Reply(answer, Math.min(1.0, top.coverage()), sources, suggestions(top.doc()), usedLlm, false);
    }


    // ---- "today", "tomorrow", "monday"... -> date tokens like oct8, so menu entries can be matched ----
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern DAY_WORDS = Pattern.compile(
            "\\b(today(?:'s)?|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
            Pattern.CASE_INSENSITIVE);

    static String dateToken(LocalDate d) {
        return d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase() + d.getDayOfMonth();
    }

    static String resolveDays(String q) { return resolveDays(q, LocalDate.now(IST)); }

    static String resolveDays(String q, LocalDate today) {
        Matcher m = DAY_WORDS.matcher(q);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String w = m.group(1).toLowerCase();
            String rep;
            if (w.startsWith("today")) rep = dateToken(today);
            else if (w.equals("tonight")) rep = "dinner " + dateToken(today);
            else if (w.equals("tomorrow")) rep = dateToken(today.plusDays(1));
            else {
                DayOfWeek target = DayOfWeek.valueOf(w.toUpperCase());
                int add = (target.getValue() - today.getDayOfWeek().getValue() + 7) % 7;   // next occurrence, today included
                rep = dateToken(today.plusDays(add));
            }
            m.appendReplacement(out, rep);
        }
        m.appendTail(out);
        return out.toString();
    }

    private List<String> suggestions(KnowledgeBase.Doc base) {
        List<KnowledgeBase.Doc> pool = base == null ? kb.all() : kb.byCategory(base.category());
        List<String> out = new ArrayList<>();
        for (var d : pool) {
            if (base != null && d.id() == base.id()) continue;
            out.add(d.title());
            if (out.size() == 3) break;
        }
        return out;
    }

    private void logGap(String q) {
        gaps.merge(q.toLowerCase(), 1, Integer::sum);
        try {
            Files.writeString(gapFile, q.replace('\n', ' ') + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    /** Most-asked unanswered questions = what to add to the knowledge base next. */
    public String gapsJson() {
        StringBuilder sb = new StringBuilder("[");
        gaps.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"question\":").append(Json.str(e.getKey())).append(",\"count\":").append(e.getValue()).append('}');
        });
        return sb.append(']').toString();
    }

    public KnowledgeBase kb() { return kb; }
    public boolean llmEnabled() { return llm.enabled(); }
}