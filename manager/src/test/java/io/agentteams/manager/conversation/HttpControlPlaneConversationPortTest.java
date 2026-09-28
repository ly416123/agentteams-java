package io.agentteams.manager.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HttpControlPlaneConversationPortTest {
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-09-28T03:00:00Z");

    @AfterEach
    void clearContext() {
        ManagerRequestContext.clear();
    }

    @Test
    void sendsLegacyMessageAsAnInternalUnifiedCommand() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(client.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        ManagerRequestContext.set(new ManagerPrincipal("alice", "tenant-a", "project-a", "team-a", Set.of()),
                "bearer");

        new HttpControlPlaneConversationPort("http://control-plane", client, new ObjectMapper()).appendMessage(
                new ControlPlaneConversationPort.MessageRequest(CONVERSATION_ID, "tenant-a", "project-a", "team-a",
                        "alice", "INTERNAL", "TEXT", "hello", "PUBLIC", "message-1", 1, NOW));

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("Bearer bearer");
        assertThat(request.getValue().uri().toString()).isEqualTo("http://control-plane/api/v1/commands");
    }
}
