package io.agentteams.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;

/** Minimal local-only entry point for verifying Manager -> DeepSeek connectivity. */
public final class ManagerSmokeApplication {
    private static final int SMOKE_MAX_TOKENS = 256;

    private ManagerSmokeApplication() {}

    public static void main(String[] args) {
        DeepSeekConfiguration configuration = DeepSeekConfiguration.fromEnvironment(System.getenv());
        DeepSeekProvider provider = configuration.createProvider(HttpClient.newHttpClient(), new ObjectMapper());
        ModelProvider.ModelResponse response = provider.complete(new ModelProvider.ModelRequest(
                "Reply with a short connectivity confirmation.", SMOKE_MAX_TOKENS));
        if (response.content().isBlank()) {
            throw new IllegalStateException("DeepSeek smoke response was empty");
        }
        System.out.println("DEEPSEEK_MANAGER_OK model=" + configuration.model());
    }
}
