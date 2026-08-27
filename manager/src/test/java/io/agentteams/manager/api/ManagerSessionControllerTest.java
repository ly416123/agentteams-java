package io.agentteams.manager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.manager.session.ManagerEventRecord;
import io.agentteams.manager.session.ManagerSessionRecord;
import io.agentteams.manager.session.ManagerSessionServiceFacade;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ManagerSessionControllerTest {
    private MockMvc mvc;
    private ManagerSessionServiceFacade facade;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        ManagerRequestContext.set(new ManagerPrincipal("actor-a", "tenant-a", "project-a", "team-a",
                Set.of("task:create")));
        facade = mock(ManagerSessionServiceFacade.class);
        sessionId = UUID.randomUUID();
        mvc = MockMvcBuilders.standaloneSetup(new ManagerSessionController(facade))
                .setControllerAdvice(new ManagerErrorHandler()).build();
    }

    @AfterEach
    void clearContext() { ManagerRequestContext.clear(); }

    @Test
    void exposesAllFiveSessionEndpointsWithIdempotencyAndVersionHeaders() throws Exception {
        ManagerSessionRecord session = ManagerSessionRecord.newSession(sessionId, "tenant-a", "project-a",
                "actor-a", Instant.parse("2026-08-26T00:00:00Z"));
        when(facade.createSession(any(), org.mockito.ArgumentMatchers.eq("session-key"))).thenReturn(session);
        when(facade.getSession(sessionId)).thenReturn(session);
        when(facade.events(sessionId, 0)).thenReturn(List.of(
                new ManagerEventRecord(sessionId, 1, "SESSION_CREATED", "{}", session.createdAt())));
        ManagerSessionServiceFacade.MessageResult message = new ManagerSessionServiceFacade.MessageResult(session,
                new io.agentteams.manager.session.ManagerMessageRecord(UUID.randomUUID(), sessionId, "message-key",
                        "actor-a", "user", "hash", "message accepted", "created", session.createdAt()), null);
        when(facade.appendMessage(org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq("message-key"), org.mockito.ArgumentMatchers.eq("actor-a"),
                org.mockito.ArgumentMatchers.eq("create"), any(), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull())).thenReturn(message);
        when(facade.cancel(sessionId, 0, "cancel-key", "actor-a")).thenReturn(
                session.withStatus(ManagerSessionRecord.Status.CANCELLED, session.updatedAt()));

        mvc.perform(post("/api/v1/manager/sessions")
                .header("Idempotency-Key", "session-key")
                .contentType("application/json")
                .content("{\"tenantId\":\"tenant-a\",\"projectId\":\"project-a\",\"actor\":\"actor-a\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(sessionId.toString()));
        mvc.perform(post("/api/v1/manager/sessions/" + sessionId + "/messages")
                .header("Idempotency-Key", "message-key")
                .contentType("application/json")
                .content("{\"content\":\"create\",\"expectedVersion\":0,\"actor\":\"actor-a\","
                        + "\"permissions\":[\"task:create\"],\"approved\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/manager/sessions/" + sessionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
        mvc.perform(get("/api/v1/manager/sessions/" + sessionId + "/events")
                .param("after", "0"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/event-stream"));
        mvc.perform(post("/api/v1/manager/sessions/" + sessionId + "/cancel")
                .header("Idempotency-Key", "cancel-key")
                .contentType("application/json")
                .content("{\"expectedVersion\":0,\"actor\":\"actor-a\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void doesNotDelegateClientPermissionSubsetAsTheVerifiedPermissionSet() throws Exception {
        ManagerSessionRecord session = ManagerSessionRecord.newSession(sessionId, "tenant-a", "project-a",
                "actor-a", Instant.parse("2026-08-26T00:00:00Z"));
        when(facade.createSession(any(), org.mockito.ArgumentMatchers.eq("session-key"))).thenReturn(session);
        ManagerSessionServiceFacade.MessageResult message = new ManagerSessionServiceFacade.MessageResult(session,
                new io.agentteams.manager.session.ManagerMessageRecord(UUID.randomUUID(), sessionId, "message-key",
                        "actor-a", "user", "hash", "message accepted", "created", session.createdAt()), null);
        when(facade.appendMessage(org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq("message-key"), org.mockito.ArgumentMatchers.eq("actor-a"),
                org.mockito.ArgumentMatchers.eq("create"), org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(message);

        mvc.perform(post("/api/v1/manager/sessions")
                .header("Idempotency-Key", "session-key")
                .contentType("application/json")
                .content("{\"tenantId\":\"tenant-a\",\"projectId\":\"project-a\",\"actor\":\"actor-a\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/manager/sessions/" + sessionId + "/messages")
                .header("Idempotency-Key", "message-key")
                .contentType("application/json")
                .content("{\"content\":\"create\",\"expectedVersion\":0,\"actor\":\"actor-a\","
                        + "\"permissions\":[],\"approved\":false}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Set<String>> permissions = org.mockito.ArgumentCaptor.forClass(Set.class);
        org.mockito.Mockito.verify(facade).appendMessage(org.mockito.ArgumentMatchers.eq(sessionId),
                org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.eq("message-key"),
                org.mockito.ArgumentMatchers.eq("actor-a"), org.mockito.ArgumentMatchers.eq("create"),
                permissions.capture(), org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
        assertThat(permissions.getValue()).containsExactlyInAnyOrder("task:create");
    }

    @Test
    void mapsMissingIdempotencyKeyToStableErrorWithCorrelationId() throws Exception {
        mvc.perform(post("/api/v1/manager/sessions")
                .contentType("application/json")
                .content("{\"tenantId\":\"tenant-a\",\"projectId\":\"project-a\",\"actor\":\"actor-a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOOL_INPUT_INVALID"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
