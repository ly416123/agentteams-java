package io.agentteams.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

public final class DeepSeekProvider extends OpenAICompatibleProvider {
    public DeepSeekProvider(String apiKey, String model, HttpClient client, ObjectMapper mapper, Duration timeout) {
        super(URI.create("https://api.deepseek.com/chat/completions"), apiKey, model, client, mapper, timeout);
    }

    @Override
    public String providerName() { return "deepseek"; }
}
