package com.stocks.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocks.tracker.model.AppSetting;
import com.stocks.tracker.repository.AppSettingRepository;
import com.stocks.tracker.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Talks to a local LLM server: either Ollama (native /api) or LM Studio
 * (OpenAI-compatible /v1). The provider can be chosen explicitly or detected
 * automatically by probing the endpoint. Settings are stored in the database.
 */
@Service
public class LlmService {

    public static final String AUTO = "auto";
    public static final String OLLAMA = "ollama";
    public static final String LMSTUDIO = "lmstudio";

    public static final String OLLAMA_DEFAULT = "http://localhost:11434";
    public static final String LMSTUDIO_DEFAULT = "http://localhost:1234";

    /**
     * Built-in instructions that are always sent first as the system prompt.
     * TODO: fill in the real prompt; the user's custom prompt is appended after it.
     */
    public static final String HARDCODED_PROMPT = """
            If asked for current and relevant information, use the tools to find the current date/time and then \
            fetch the relevant information. Training data may be outdated.

            You have access to a wide variety of tools for coding/math/physics/etc...use them. Do not hallucinate \
            and give made up answers.

            For most questions make sure to think about an answer as the solution may not be obvious. e.g. "I need \
            to take my car to the car wash and the car wash is 100m away. should I walk or drive there?" the answer \
            should be "Drive" as you need to drive the car to wash it.

            Always use html_to_markdown to get a clean markdown of any webpage you get with the web_search tool.
            """;

    // Keys keep their original "ollama." prefix so previously saved settings still load.
    private static final String KEY_ENDPOINT = "ollama.endpoint";
    private static final String KEY_MODEL = "ollama.model";
    /** Custom prompts are per user: the key is this prefix + the user id + the suffix. */
    private static final String KEY_CUSTOM_PROMPT_PREFIX = "user.";
    private static final String KEY_CUSTOM_PROMPT_SUFFIX = ".customPrompt";

    public static final String DEFAULT_CUSTOM_PROMPT =
            "I would like to invest for the long term. Along with the analysis, look at other stocks in the market which I should invest in too.";
    private static final String KEY_PROVIDER = "ai.provider";

    private final AppSettingRepository settings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final AiContextProperties limits;

    private final CurrentUserService currentUser;

    public LlmService(AppSettingRepository settings, AiContextProperties limits, CurrentUserService currentUser) {
        this.settings = settings;
        this.limits = limits;
        this.currentUser = currentUser;
    }

    public record Config(String provider, String endpoint, String model) {
    }

    /** provider/endpoint describe what was actually reached (may differ from what was requested in auto mode). */
    public record Status(boolean connected, String provider, String endpoint, String version,
                         List<String> models, String error) {
    }

    public record ChatResult(String model, String response, long durationMs) {
    }

    @Transactional(readOnly = true)
    public Config getConfig() {
        String provider = settings.findById(KEY_PROVIDER).map(AppSetting::getValue).orElse(AUTO);
        String endpoint = settings.findById(KEY_ENDPOINT).map(AppSetting::getValue).orElse(OLLAMA_DEFAULT);
        String model = settings.findById(KEY_MODEL).map(AppSetting::getValue).orElse("");
        return new Config(provider, endpoint, model);
    }

    @Transactional
    public Config saveConfig(String provider, String endpoint, String model) {
        String normalized = normalizeEndpoint(endpoint);
        String cleanModel = model == null ? "" : model.trim();
        settings.save(new AppSetting(KEY_PROVIDER, normalizeProvider(provider)));
        settings.save(new AppSetting(KEY_ENDPOINT, normalized));
        settings.save(new AppSetting(KEY_MODEL, cleanModel));
        return getConfig();
    }

    private static String customPromptKey(Long userId) {
        return KEY_CUSTOM_PROMPT_PREFIX + userId + KEY_CUSTOM_PROMPT_SUFFIX;
    }

    /** The signed-in user's custom prompt: the default until they save their own (which may be empty). */
    @Transactional(readOnly = true)
    public String getCustomPrompt() {
        return settings.findById(customPromptKey(currentUser.currentUserId()))
                .map(AppSetting::getValue)
                .map(v -> v == null ? "" : v)
                .orElse(DEFAULT_CUSTOM_PROMPT);
    }

    @Transactional
    public String saveCustomPrompt(String customPrompt) {
        String text = customPrompt == null ? "" : customPrompt.strip();
        if (text.length() > 1000) {
            throw new IllegalArgumentException("Custom prompt is limited to 1000 characters.");
        }
        settings.save(new AppSetting(customPromptKey(currentUser.currentUserId()), text));
        return text;
    }

    /** Hardcoded prompt followed by the user's custom prompt; blank parts are skipped. */
    public static String buildSystemPrompt(String customPrompt) {
        String custom = customPrompt == null ? "" : customPrompt.strip();
        if (HARDCODED_PROMPT.isBlank()) {
            return custom;
        }
        return custom.isEmpty() ? HARDCODED_PROMPT.strip() : HARDCODED_PROMPT.strip() + "\n\n" + custom;
    }

    static String normalizeProvider(String provider) {
        String p = provider == null ? AUTO : provider.trim().toLowerCase();
        if (!p.equals(AUTO) && !p.equals(OLLAMA) && !p.equals(LMSTUDIO)) {
            throw new IllegalArgumentException("Unknown provider: " + provider);
        }
        return p;
    }

    static String normalizeEndpoint(String endpoint) {
        String e = endpoint == null ? "" : endpoint.trim();
        if (e.isEmpty()) {
            throw new IllegalArgumentException("Endpoint URL is required.");
        }
        if (!e.contains("://")) {
            e = "http://" + e;
        }
        URI uri;
        try {
            uri = URI.create(e);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Endpoint is not a valid URL.");
        }
        String scheme = uri.getScheme();
        if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            throw new IllegalArgumentException("Endpoint must be an http:// or https:// URL, e.g. http://localhost:11434");
        }
        while (e.endsWith("/")) {
            e = e.substring(0, e.length() - 1);
        }
        return e;
    }

    /**
     * Connects to the endpoint with the given provider (or detects it when "auto") and lists
     * installed models. In auto mode, if a default local endpoint of one provider is
     * unreachable, the other provider's default endpoint is tried as well.
     */
    public Status checkStatus(String endpointOverride, String providerOverride) {
        String provider;
        String endpoint;
        try {
            Config saved = getConfig();
            provider = normalizeProvider(providerOverride == null || providerOverride.isBlank()
                    ? saved.provider() : providerOverride);
            endpoint = endpointOverride == null || endpointOverride.isBlank()
                    ? saved.endpoint() : normalizeEndpoint(endpointOverride);
        } catch (IllegalArgumentException e) {
            return new Status(false, null, null, null, List.of(), e.getMessage());
        }

        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(endpoint);
        if (provider.equals(AUTO)) {
            if (endpoint.equals(OLLAMA_DEFAULT)) {
                candidates.add(LMSTUDIO_DEFAULT);
            } else if (endpoint.equals(LMSTUDIO_DEFAULT)) {
                candidates.add(OLLAMA_DEFAULT);
            }
        }

        String firstError = null;
        for (String candidate : candidates) {
            List<String> tryProviders = provider.equals(AUTO) ? List.of(OLLAMA, LMSTUDIO) : List.of(provider);
            for (String p : tryProviders) {
                try {
                    return probe(p, candidate);
                } catch (Exception e) {
                    if (firstError == null) {
                        firstError = describe(e, candidate, p);
                    }
                }
            }
        }
        return new Status(false, null, endpoint, null, List.of(), firstError);
    }

    private Status probe(String provider, String endpoint) throws IOException, InterruptedException {
        List<String> models = new ArrayList<>();
        if (provider.equals(OLLAMA)) {
            JsonNode version = get(endpoint + "/api/version");
            JsonNode tags = get(endpoint + "/api/tags");
            // Other servers (e.g. LM Studio) may answer these paths with unrelated JSON.
            if (!version.hasNonNull("version") || !tags.path("models").isArray()) {
                throw new IOException("Not an Ollama server: " + endpoint);
            }
            for (JsonNode m : tags.path("models")) {
                models.add(m.path("name").asText());
            }
            return new Status(true, OLLAMA, endpoint, version.path("version").asText(""), models, null);
        }
        JsonNode list = get(endpoint + "/v1/models");
        if (!list.path("data").isArray()) {
            throw new IOException("Unexpected response from " + endpoint + "/v1/models");
        }
        for (JsonNode m : list.path("data")) {
            models.add(m.path("id").asText());
        }
        return new Status(true, LMSTUDIO, endpoint, "", models, null);
    }

    /** One turn of a conversation; role is "user" or "assistant". */
    public record Message(String role, String content) {
    }

    public ChatResult chat(String prompt) {
        return chat(prompt, Duration.ofSeconds(180));
    }

    /** @param timeout maximum time to wait for the model's reply; null waits indefinitely (slow local models) */
    public ChatResult chat(String prompt, Duration timeout) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt is required.");
        }
        return chat(java.util.List.of(new Message("user", prompt)), null, timeout);
    }

    /**
     * Multi-turn chat. {@code extraSystem}, if given, is added after the app's system prompt and the
     * user's custom prompt (used to supply current data). The conversation must end with a user turn.
     */
    public ChatResult chat(java.util.List<Message> conversation, String extraSystem, Duration timeout) {
        Config config = getConfig();
        if (conversation == null || conversation.isEmpty() || !"user".equals(conversation.get(conversation.size() - 1).role())) {
            throw new IllegalArgumentException("Prompt is required.");
        }
        if (config.model().isBlank()) {
            throw new IllegalArgumentException("Select and save a model first.");
        }

        String provider = config.provider();
        String endpoint = config.endpoint();
        if (provider.equals(AUTO)) {
            Status status = checkStatus(config.endpoint(), AUTO);
            if (!status.connected()) {
                throw new IllegalStateException(status.error());
            }
            provider = status.provider();
            endpoint = status.endpoint();
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model());
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        String system = buildSystemPrompt(getCustomPrompt());
        if (extraSystem != null && !extraSystem.isBlank()) {
            system = system.isEmpty() ? extraSystem.strip() : system + "\n\n" + extraSystem.strip();
        }
        if (!system.isEmpty()) {
            messages.addObject().put("role", "system").put("content", system);
        }
        for (Message m : conversation) {
            messages.addObject().put("role", m.role()).put("content", m.content());
        }

        if (provider.equals(OLLAMA)) {
            // Ollama silently truncates prompts to its small default context, which would cut off the
            // instructions at the start of a long prompt, so ask for a window sized to this request.
            int needed = TokenEstimator.estimate(system);
            for (Message m : conversation) {
                needed += TokenEstimator.estimate(m.content());
            }
            needed += limits.getOutputReserveTokens();
            int numCtx = Math.min(limits.getWindowTokens(), Math.max(8192, (needed + 2047) / 2048 * 2048));
            body.putObject("options").put("num_ctx", numCtx);
        }

        String path = provider.equals(OLLAMA) ? "/api/chat" : "/v1/chat/completions";
        long start = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (timeout != null) {
                builder.timeout(timeout);
            }
            HttpRequest request = builder.build();
            JsonNode json = send(request);
            String text = provider.equals(OLLAMA)
                    ? json.path("message").path("content").asText("")
                    : json.path("choices").path(0).path("message").path("content").asText("");
            return new ChatResult(config.model(), text, System.currentTimeMillis() - start);
        } catch (Exception e) {
            throw new IllegalStateException(describe(e, endpoint, provider), e);
        }
    }

    private JsonNode get(String url) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build());
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            String detail = response.body();
            try {
                JsonNode err = mapper.readTree(response.body()).path("error");
                detail = err.isObject() ? err.path("message").asText(detail) : err.asText(detail);
            } catch (Exception ignored) {
                // body was not JSON; use it as-is
            }
            throw new IOException("HTTP " + response.statusCode() + ": " + detail);
        }
        return mapper.readTree(response.body());
    }

    private static String describe(Exception e, String endpoint, String provider) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        String name = provider.equals(OLLAMA) ? "Ollama" : "LM Studio";
        if (e instanceof java.net.ConnectException || e instanceof java.net.http.HttpConnectTimeoutException) {
            return "Could not connect to " + endpoint + ". Is " + name + " running?";
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "Timed out waiting for " + endpoint;
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
