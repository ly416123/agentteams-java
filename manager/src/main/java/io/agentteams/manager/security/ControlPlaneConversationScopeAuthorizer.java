package io.agentteams.manager.security;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Resolves stable Console resource IDs through Control Plane authorization. */
public final class ControlPlaneConversationScopeAuthorizer implements ConversationScopeAuthorizer {
    private final URI controlPlaneBase;
    private final HttpClient client;

    public ControlPlaneConversationScopeAuthorizer(String controlPlaneUrl, HttpClient client) {
        if (controlPlaneUrl == null || controlPlaneUrl.isBlank()) {
            throw new IllegalArgumentException("AGENTTEAMS_CONTROL_PLANE_URL must be set");
        }
        this.controlPlaneBase = URI.create(controlPlaneUrl.replaceAll("/+$", ""));
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void requireAccessible(String projectId, String teamId, ManagerPrincipal principal) {
        Objects.requireNonNull(principal, "principal");
        if (principal.projectId().equals(projectId) && principal.teamId().equals(teamId)) return;

        UUID teamUuid;
        try {
            teamUuid = UUID.fromString(Objects.requireNonNull(teamId, "teamId"));
        } catch (IllegalArgumentException error) {
            throw new ManagerAuthorizationException("conversation scope is not accessible");
        }
        String encodedProject = URLEncoder.encode(Objects.requireNonNull(projectId, "projectId"),
                StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(controlPlaneBase.resolve(
                "/api/v1/teams/" + teamUuid + "?projectId=" + encodedProject))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + ManagerRequestContext.requireBearerToken())
                .GET()
                .build();
        try {
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) return;
            if (status >= 400 && status < 500) {
                throw new ManagerAuthorizationException("conversation scope is not accessible");
            }
            throw new ManagerScopeUnavailableException("Control Plane scope check failed", null);
        } catch (ManagerAuthorizationException | ManagerScopeUnavailableException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ManagerScopeUnavailableException("Control Plane scope check was interrupted", error);
        } catch (IOException | RuntimeException error) {
            throw new ManagerScopeUnavailableException("Control Plane scope check failed", error);
        }
    }
}
