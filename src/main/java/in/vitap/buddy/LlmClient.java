package in.vitap.buddy;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Optional LLM layer (Anthropic Messages API). If no API key is set the app still works offline. */
public class LlmClient {
    private final String apiKey = System.getenv("ANTHROPIC_API_KEY");
    private final String model = System.getenv().getOrDefault("CLAUDE_MODEL", "claude-sonnet-5-5");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public boolean enabled() { return apiKey != null && !apiKey.isBlank(); }

    public String complete(String system, String user) throws Exception {
        String body = "{\"model\":" + Json.str(model) + ",\"max_tokens\":700,\"system\":" + Json.str(system)
                + ",\"messages\":[{\"role\":\"user\",\"content\":" + Json.str(user) + "}]}";
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .timeout(Duration.ofSeconds(40))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new RuntimeException("LLM error " + res.statusCode() + ": " + res.body());
        return Json.field(res.body(), "text");
    }
}
