package io.agentteams.worker.chatgpt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Reads the current access-token file from a projected Kubernetes Secret volume. */
@FunctionalInterface
public interface ChatGptPlanTokenSource {
    String accessToken();

    static ChatGptPlanTokenSource fromPath(Path path) {
        Path tokenPath = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        return () -> {
            try {
                String token = Files.readString(tokenPath).trim();
                if (token.isEmpty()) throw new IOException("empty token");
                return token;
            } catch (IOException | SecurityException error) {
                throw new IllegalStateException("ChatGPT plan access token is unavailable");
            }
        };
    }
}
