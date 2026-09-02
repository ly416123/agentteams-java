package io.agentteams.manager.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ControlPlaneConversationScopeAuthorizerTest {
    private static final ManagerPrincipal PRINCIPAL = new ManagerPrincipal(
            "alice", "tenant-a", "project-a", "team-a", Set.of("conversation:write"));
    private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000201";
    private static final String TEAM_ID = "00000000-0000-0000-0000-000000000202";

    @AfterEach
    void clearContext() {
        ManagerRequestContext.clear();
    }

    @Test
    void forwardsCallerTokenAndStableProjectScopeToControlPlane() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> response = response(204);
        when(client.<Void>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        ManagerRequestContext.set(PRINCIPAL, "development-token");

        new ControlPlaneConversationScopeAuthorizer("http://control-plane:8080/", client)
                .requireAccessible(PROJECT_ID, TEAM_ID, PRINCIPAL);

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().method()).isEqualTo("GET");
        assertThat(request.getValue().uri().toString())
                .isEqualTo("http://control-plane:8080/api/v1/teams/" + TEAM_ID + "?projectId=" + PROJECT_ID);
        assertThat(request.getValue().headers().firstValue("Authorization"))
                .contains("Bearer development-token");
    }

    @Test
    void rejectsStableScopeWhenControlPlaneRejectsCaller() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> response = response(403);
        when(client.<Void>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        ManagerRequestContext.set(PRINCIPAL, "development-token");

        assertThatThrownBy(() -> new ControlPlaneConversationScopeAuthorizer("http://control-plane:8080", client)
                .requireAccessible(PROJECT_ID, TEAM_ID, PRINCIPAL))
                .isInstanceOf(ManagerAuthorizationException.class);
    }

    @Test
    void exposesDependencyFailureWhenControlPlaneIsUnavailable() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> response = response(503);
        when(client.<Void>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        ManagerRequestContext.set(PRINCIPAL, "development-token");

        assertThatThrownBy(() -> new ControlPlaneConversationScopeAuthorizer("http://control-plane:8080", client)
                .requireAccessible(PROJECT_ID, TEAM_ID, PRINCIPAL))
                .isInstanceOf(ManagerScopeUnavailableException.class);
    }

    @Test
    void doesNotCallControlPlaneForLegacyExternalScope() {
        HttpClient client = mock(HttpClient.class);

        new ControlPlaneConversationScopeAuthorizer("http://control-plane:8080", client)
                .requireAccessible("project-a", "team-a", PRINCIPAL);

        verifyNoInteractions(client);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<Void> response(int status) {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        return response;
    }
}
