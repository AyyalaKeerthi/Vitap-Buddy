package in.vitap.buddy;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class App {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8090"));
        Agent agent = new Agent(KnowledgeBase.load("/knowledge.txt"), new LlmClient());
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", ex -> {
            byte[] page;
            try (InputStream in = App.class.getResourceAsStream("/static/index.html")) { page = in.readAllBytes(); }
            send(ex, 200, "text/html; charset=utf-8", page);
        });

        server.createContext("/api/ask", ex -> {
            if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, "text/plain", "POST only".getBytes()); return; }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String question = Json.field(body, "question");
            if (question.isBlank()) { send(ex, 400, "application/json", "{\"error\":\"empty question\"}".getBytes()); return; }
            Agent.Reply r = agent.ask(question, Json.field(body, "profile"));
            String sources = r.sources().stream()
                    .map(d -> "{\"title\":" + Json.str(d.title()) + ",\"source\":" + Json.str(d.source()) + "}")
                    .collect(Collectors.joining(",", "[", "]"));
            String follow = r.followUps().stream().map(Json::str).collect(Collectors.joining(",", "[", "]"));
            String json = "{\"answer\":" + Json.str(r.answer()) + ",\"confidence\":" + String.format("%.2f", r.confidence())
                    + ",\"sources\":" + sources + ",\"followUps\":" + follow
                    + ",\"usedLlm\":" + r.usedLlm() + ",\"gap\":" + r.gap() + "}";
            send(ex, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        });

        // Admin view: what are freshers asking that we can't answer yet?
        server.createContext("/api/gaps", ex ->
                send(ex, 200, "application/json; charset=utf-8", agent.gapsJson().getBytes(StandardCharsets.UTF_8)));

        server.start();
        System.out.println("VIT-AP Buddy running at http://localhost:" + port
                + (agent.llmEnabled() ? "  (LLM mode)" : "  (offline retrieval mode - set ANTHROPIC_API_KEY for AI answers)"));
    }

    private static void send(HttpExchange ex, int code, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }
}
