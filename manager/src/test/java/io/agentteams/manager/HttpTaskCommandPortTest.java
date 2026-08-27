package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.application.api.TaskCommandPort;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HttpTaskCommandPortTest {
    @AfterEach
    void clearManagerContext() {
        ManagerRequestContext.clear();
    }

    @Test
    void forwardsVerifiedBearerTokenToControlPlane() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        UUID taskId = UUID.randomUUID();
        when(response.statusCode()).thenReturn(201);
        when(response.body()).thenReturn("{\"id\":\"" + taskId + "\",\"phase\":\"DRAFT\",\"version\":0}");
        when(client.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        ManagerRequestContext.set(new ManagerPrincipal("alice", "tenant-a", "project-a", "team-a",
                Set.of("task:create")), "signed-bearer-token");

        new HttpTaskCommandPort("http://control-plane", client, new ObjectMapper()).create("manager-key",
                new TaskCommandPort.TaskCreateCommand("title", "description", "{\"scope\":{}}",
                        "manager", "manager"));

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().headers().firstValue("Authorization"))
                .contains("Bearer signed-bearer-token");
    }

    @Test
    void refusesToCallControlPlaneWithoutRequestBearerToken() {
        HttpClient client = mock(HttpClient.class);
        HttpTaskCommandPort port = new HttpTaskCommandPort("http://control-plane", client, new ObjectMapper());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> port.create("manager-key",
                new TaskCommandPort.TaskCreateCommand("title", "description", "{\"scope\":{}}",
                        "manager", "manager")))
                .isInstanceOf(io.agentteams.manager.security.ManagerAuthenticationException.class);
    }
}
