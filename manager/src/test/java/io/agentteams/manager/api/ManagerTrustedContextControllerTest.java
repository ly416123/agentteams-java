package io.agentteams.manager.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import io.agentteams.manager.session.ManagerSessionRecord;
import io.agentteams.manager.session.ManagerSessionServiceFacade;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ManagerTrustedContextControllerTest {
    private ManagerSessionServiceFacade facade;
    private MockMvc mvc;
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        facade = mock(ManagerSessionServiceFacade.class);
        mvc = MockMvcBuilders.standaloneSetup(new ManagerSessionController(facade))
                .setControllerAdvice(new ManagerErrorHandler()).build();
    }

    @AfterEach
    void clearContext() {
        ManagerRequestContext.clear();
    }

    @Test
    void rejectsMissingTrustedContextAsAuthenticationError() throws Exception {
        mvc.perform(post("/api/v1/manager/sessions")
                .header("Idempotency-Key", "session-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"tenant-a\",\"projectId\":\"project-a\",\"actor\":\"user\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void rejectsClientSuppliedScopeAndActorInsteadOfTrustingRequestBody() throws Exception {
        ManagerRequestContext.set(new ManagerPrincipal("trusted-user", "tenant-a", "project-a",
                "team-a", Set.of("task:create")));

        mvc.perform(post("/api/v1/manager/sessions")
                .header("Idempotency-Key", "session-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"tenant-b\",\"projectId\":\"project-b\",\"actor\":\"attacker\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_REJECTED"));
    }

    @Test
    void usesAuthenticatedIdentityAndPermissionsForMessageCommand() throws Exception {
        ManagerRequestContext.set(new ManagerPrincipal("trusted-user", "tenant-a", "project-a",
                "team-a", Set.of("task:create")));
        ManagerSessionRecord session = ManagerSessionRecord.newSession(sessionId, "tenant-a", "project-a",
                "trusted-user", Instant.parse("2026-08-26T00:00:00Z"));
        when(facade.createSession(any(), org.mockito.ArgumentMatchers.eq("session-key"))).thenReturn(session);

        mvc.perform(post("/api/v1/manager/sessions")
                .header("Idempotency-Key", "session-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":\"tenant-a\",\"projectId\":\"project-a\",\"actor\":\"trusted-user\"}"))
                .andExpect(status().isCreated());

        verify(facade).createSession(
                org.mockito.ArgumentMatchers.argThat(command -> command.actor().equals("trusted-user")
                        && command.tenantId().equals("tenant-a") && command.projectId().equals("project-a")),
                org.mockito.ArgumentMatchers.eq("session-key"));
    }
}
