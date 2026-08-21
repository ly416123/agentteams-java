package io.agentteams.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** Environment-backed local configuration for the DeepSeek Manager provider. */
public final class DeepSeekConfiguration {
    public static final String DEFAULT_MODEL = "deepseek-v4-flash";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String apiKey;
    private final String model;
    private final Duration timeout;

    private DeepSeekConfiguration(String apiKey, String model, Duration timeout) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
    }

    public static DeepSeekConfiguration fromEnvironment(Map<String, String> environment) {
        if (environment == null) throw new IllegalArgumentException("environment must not be null");

        String apiKey = text(environment.get("DEEPSEEK_API_KEY"));
        if (apiKey == null) throw new IllegalArgumentException("DEEPSEEK_API_KEY must be set");

        String model = text(environment.get("DEEPSEEK_MODEL"));
        if (model == null) model = DEFAULT_MODEL;

        String timeoutValue = text(environment.get("DEEPSEEK_TIMEOUT_SECONDS"));
        Duration timeout = DEFAULT_TIMEOUT;
        if (timeoutValue != null) {
            try {
                long seconds = Long.parseLong(timeoutValue);
                if (seconds <= 0) throw new NumberFormatException();
                timeout = Duration.ofSeconds(seconds);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                        "DEEPSEEK_TIMEOUT_SECONDS must be a positive integer");
            }
        }
        return new DeepSeekConfiguration(apiKey, model, timeout);
    }

    public String apiKey() { return apiKey; }

    public String model() { return model; }

    public Duration timeout() { return timeout; }

    public DeepSeekProvider createProvider(HttpClient client, ObjectMapper mapper) {
        return new DeepSeekProvider(apiKey, model, client, mapper, timeout);
    }

    private static String text(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
