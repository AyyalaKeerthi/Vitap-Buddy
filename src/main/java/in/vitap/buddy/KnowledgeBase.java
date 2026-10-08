package in.vitap.buddy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Loads university facts and retrieves the best matches using BM25 + synonym expansion. */
public class KnowledgeBase {

    public record Doc(int id, String category, String title, String keywords, String answer, String source) {}
    public record Hit(Doc doc, double score, double coverage) {}

    private static final Set<String> STOP = Set.of("s","t","a","an","the","is","are","am","i","me","my","to","of","in","on","at",
            "for","and","or","do","does","how","what","where","when","can","will","there","it","this","that","about",
            "please","tell","with","be","have","has","you","we","get","need","want","hi","hello","which","who");

    private static final Map<String, List<String>> SYN = Map.ofEntries(
            Map.entry("mess", List.of("food","dining","canteen")),
            Map.entry("food", List.of("mess","dining","canteen")),
            Map.entry("wifi", List.of("internet","network")),
            Map.entry("internet", List.of("wifi","network")),
            Map.entry("fee", List.of("payment","tuition","pay")),
            Map.entry("pay", List.of("fee","payment")),
            Map.entry("hostel", List.of("room","accommodation","warden")),
            Map.entry("room", List.of("hostel","accommodation")),
            Map.entry("bus", List.of("transport","shuttle")),
            Map.entry("transport", List.of("bus","shuttle")),
            Map.entry("attendance", List.of("present","75")),
            Map.entry("club", List.of("chapter","society","activity")),
            Map.entry("laundry", List.of("washing","clothes")),
            Map.entry("doctor", List.of("medical","health","hospital")),
            Map.entry("medical", List.of("doctor","health","hospital")),
            Map.entry("ragging", List.of("antiragging","harassment","complaint")),
            Map.entry("timetable", List.of("schedule","slot","class")),
            Map.entry("exam", List.of("cat","fat","test","assessment")),
            Map.entry("library", List.of("books","reading")),
            Map.entry("placement", List.of("job","internship","recruit"))
    );

    private final List<Doc> docs = new ArrayList<>();
    private final List<Map<String, Integer>> tf = new ArrayList<>();
    private final Map<String, Integer> df = new HashMap<>();
    private double avgLen;

    public static KnowledgeBase load(String resource) throws IOException {
        KnowledgeBase kb = new KnowledgeBase();
        try (InputStream in = KnowledgeBase.class.getResourceAsStream(resource)) {
            if (in == null) throw new FileNotFoundException(resource);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line; int id = 0;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\|", 5);
                if (p.length < 4) continue;
                kb.docs.add(new Doc(id++, p[0].trim(), p[1].trim(), p[2].trim(), p[3].trim().replace("\\n", "\n"), p.length > 4 ? p[4].trim() : ""));
            }
        }
        kb.index();
        return kb;
    }

    private void index() {
        long total = 0;
        for (Doc d : docs) {
            Map<String, Integer> m = new HashMap<>();
            // title x3, keywords x2, answer x1 -> title/keywords matter more
            for (int i = 0; i < 3; i++) tokenize(d.title()).forEach(t -> m.merge(t, 1, Integer::sum));
            for (int i = 0; i < 2; i++) tokenize(d.keywords()).forEach(t -> m.merge(t, 1, Integer::sum));
            tokenize(d.answer()).forEach(t -> m.merge(t, 1, Integer::sum));
            tf.add(m);
            total += m.values().stream().mapToInt(Integer::intValue).sum();
            m.keySet().forEach(t -> df.merge(t, 1, Integer::sum));
        }
        avgLen = docs.isEmpty() ? 1 : (double) total / docs.size();
    }

    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (t.isEmpty() || STOP.contains(t)) continue;
            if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) t = t.substring(0, t.length() - 1);
            out.add(t);
        }
        return out;
    }

    public List<Hit> search(String query, String profileTokens, int k) {
        List<String> q = tokenize(query);
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String t : q) {
            weights.put(t, 1.0);
            for (String s : SYN.getOrDefault(t, List.of())) weights.putIfAbsent(s, 0.5);
        }
        // personalisation: profile tokens (e.g. "girls hostel") nudge ranking
        for (String t : tokenize(profileTokens)) weights.putIfAbsent(t, 0.3);

        int n = docs.size();
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> m = tf.get(i);
            double len = m.values().stream().mapToInt(Integer::intValue).sum();
            double score = 0;
            for (var e : weights.entrySet()) {
                Integer f = m.get(e.getKey());
                if (f == null) continue;
                double idf = Math.log(1 + (n - df.get(e.getKey()) + 0.5) / (df.get(e.getKey()) + 0.5));
                double norm = f * 2.5 / (f + 1.5 * (0.25 + 0.75 * len / avgLen));
                score += e.getValue() * idf * norm;
            }
            if (score <= 0) continue;
            int matched = 0;
            for (String t : q) {
                if (m.containsKey(t) || SYN.getOrDefault(t, List.of()).stream().anyMatch(m::containsKey)) matched++;
            }
            double cov = q.isEmpty() ? 0 : (double) matched / q.size();
            hits.add(new Hit(docs.get(i), score, cov));
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return hits.subList(0, Math.min(k, hits.size()));
    }

    public List<Doc> byCategory(String category) {
        return docs.stream().filter(d -> d.category().equalsIgnoreCase(category)).toList();
    }

    public List<Doc> all() { return docs; }
}