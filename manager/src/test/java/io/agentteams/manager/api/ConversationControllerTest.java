package io.agentteams.manager.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.manager.conversation.ConversationRuntimePort;
import io.agentteams.manager.conversation.ConversationService;
import io.agentteams.manager.conversation.FakeConversationRuntime;
import io.agentteams.manager.security.ConversationScopeAuthorizer;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

class ConversationControllerTest {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private ConversationService service;
    private ConversationScopeAuthorizer scopeAuthorizer;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = new ConversationService(new FakeConversationRuntime());
        scopeAuthorizer = mock(ConversationScopeAuthorizer.class);
        doAnswer(invocation -> {
            ConversationScopeAuthorizer.legacy().requireAccessible(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(scopeAuthorizer).requireAccessible(any(), any(), any());
        mvc = MockMvcBuilders.standaloneSetup(new ConversationController(service, new ObjectMapper(), scopeAuthorizer))
                .setControllerAdvice(new ManagerErrorHandler()).build();
        ManagerRequestContext.set(new ManagerPrincipal("manager-a", "tenant-a", "project-a", "team-a",
                Set.of("conversation:write")));
    }

    @AfterEach
    void clearContext() {
        ManagerRequestContext.clear();
    }

    @Test
    void createsAndGetsConversationWithAuthenticatedScope() throws Exception {
        mvc.perform(post("/api/v1/conversations")
                .header(IDEMPOTENCY_KEY, "create-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("project-a", "team-a")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.context.project").value("project-a"))
                .andExpect(jsonPath("$.context.team").value("team-a"))
                .andExpect(jsonPath("$.context.worker").value("worker-a"))
                .andExpect(jsonPath("$.context.task").value("task-a"))
                .andExpect(jsonPath("$.context.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(get("/api/v1/conversations/{sessionId}", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.context.project").value("project-a"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createReusesTheSameSessionAndContextOnRetry() throws Exception {
        String body = createBody("project-a", "team-a");

        mvc.perform(post("/api/v1/conversations")
                .header(IDEMPOTENCY_KEY, "create-key")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/conversations")
                .header(IDEMPOTENCY_KEY, "create-key-retry")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(get("/api/v1/conversations/{sessionId}/events", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(content().string("id: 1\nevent: conversation.started\ndata: {}\n\n"));
    }

    @Test
    void createIdempotencyKeyCannotCreateASecondSessionAfterRetry() throws Exception {
        createConversation();
        UUID secondSession = UUID.fromString("00000000-0000-0000-0000-000000000102");

        mvc.perform(post("/api/v1/conversations")
                .header(IDEMPOTENCY_KEY, "create-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(secondSession, "project-a", "team-a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void rejectsMissingAuthenticationAndOutOfScopeCreation() throws Exception {
        ManagerRequestContext.clear();
        mvc.perform(post("/api/v1/conversations")
                .header(IDEMPOTENCY_KEY, "create-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("project-a", "team-a")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        ManagerRequestContext.set(new ManagerPrincipal("manager-a", "tenant-a", "project-a", "team-a",
                Set.of("conversation:write")));
        mvc.perform(post("/api/v1/conversations")
                .header(IDEMPOTENCY_KEY, "create-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("project-b", "team-b")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_REJECTED"));
    }

    @Test
    void acceptsStableConsoleIdsAfterControlPlaneAuthorizesExternalOidcScope() throws Exception {
        String projectId = "00000000-0000-0000-0000-000000000201";
        String teamId = "00000000-0000-0000-0000-000000000202";
        doNothing().when(scopeAuthorizer).requireAccessible(eq(projectId), eq(teamId), any());
        mvc.perform(post("/api/v1/conversations")
                .header(IDEMPOTENCY_KEY, "stable-scope-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"" + SESSION_ID
                        + "\",\"project\":\"" + projectId + "\",\"team\":\"" + teamId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.context.project").value(projectId))
                .andExpect(jsonPath("$.context.team").value(teamId));

        verify(scopeAuthorizer).requireAccessible(projectId, teamId,
                new ManagerPrincipal("manager-a", "tenant-a", "project-a", "team-a",
                        Set.of("conversation:write")));
    }

    @Test
    void requiresIdempotencyKeyAndSessionId() throws Exception {
        mvc.perform(post("/api/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"project\":\"project-a\",\"team\":\"team-a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOOL_INPUT_INVALID"));

        mvc.perform(post("/api/v1/conversations")
                .header(IDEMPOTENCY_KEY, "create-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"project\":\"project-a\",\"team\":\"team-a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOOL_INPUT_INVALID"));
    }

    @Test
    void sendsIdempotentMessageAndReturnsOnlyConversationEvents() throws Exception {
        createConversation();

        mvc.perform(post("/api/v1/conversations/{sessionId}/messages", SESSION_ID)
                .header(IDEMPOTENCY_KEY, "message-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.idempotencyKey").value("message-key"))
                .andExpect(jsonPath("$.events[0].id").value(2))
                .andExpect(jsonPath("$.events[0].event").value("message.delta"))
                .andExpect(jsonPath("$.events[0].data.text").value("FAKE: hello"))
                .andExpect(jsonPath("$.events[0].sessionId").doesNotExist())
                .andExpect(jsonPath("$.events[0].occurredAt").doesNotExist());

        mvc.perform(post("/api/v1/conversations/{sessionId}/messages", SESSION_ID)
                .header(IDEMPOTENCY_KEY, "message-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.events.length()").value(2));
    }

    @Test
    void returnsConversationHistoryForReloadRecovery() throws Exception {
        createConversation();
        mvc.perform(post("/api/v1/conversations/{sessionId}/messages", SESSION_ID)
                .header(IDEMPOTENCY_KEY, "message-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/conversations/{sessionId}/history", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].idempotencyKey").value("message-key"))
                .andExpect(jsonPath("$.messages[0].content").value("hello"))
                .andExpect(jsonPath("$.events[0].event").value("conversation.started"))
                .andExpect(jsonPath("$.events[2].event").value("message.completed"));
    }

    @Test
    void streamsEventsUsingTheGreatestAfterAndLastEventIdCursor() throws Exception {
        createConversation();
        mvc.perform(post("/api/v1/conversations/{sessionId}/messages", SESSION_ID)
                .header(IDEMPOTENCY_KEY, "message-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/conversations/{sessionId}/events", SESSION_ID)
                .param("after", "1")
                .header("Last-Event-ID", "2"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string("id: 3\nevent: message.completed\ndata: {\"text\":\"FAKE: hello\"}\n\n"))
                .andExpect(content().string(not(containsString("id: 2"))));
    }

    @Test
    void rejectsNegativeCursorAndUnknownSession() throws Exception {
        mvc.perform(get("/api/v1/conversations/{sessionId}/events", SESSION_ID).param("after", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOOL_INPUT_INVALID"));
        mvc.perform(get("/api/v1/conversations/{sessionId}/events", SESSION_ID)
                .header("Last-Event-ID", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOOL_INPUT_INVALID"));
        mvc.perform(get("/api/v1/conversations/{sessionId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void preventsADifferentPrincipalFromReadingAnotherScope() throws Exception {
        createConversation();
        ManagerRequestContext.set(new ManagerPrincipal("manager-b", "tenant-a", "project-b", "team-b",
                Set.of("conversation:write")));

        mvc.perform(get("/api/v1/conversations/{sessionId}", SESSION_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_REJECTED"));
    }

    @Test
    void preventsADifferentTenantFromReadingAnOtherwiseMatchingConversation() throws Exception {
        createConversation();
        ManagerRequestContext.set(new ManagerPrincipal("manager-b", "tenant-b", "project-a", "team-a",
                Set.of("conversation:write")));

        mvc.perform(get("/api/v1/conversations/{sessionId}", SESSION_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_REJECTED"));
    }

    @Test
    void preventsADifferentSubjectFromReadingAnOtherwiseMatchingConversation() throws Exception {
        createConversation();
        ManagerRequestContext.set(new ManagerPrincipal("manager-b", "tenant-a", "project-a", "team-a",
                Set.of("conversation:write")));

        mvc.perform(get("/api/v1/conversations/{sessionId}", SESSION_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_REJECTED"));
    }

    @Test
    void rejectsStaleConversationVersionAndReturnsTheCurrentVersion() throws Exception {
        createConversation();

        mvc.perform(post("/api/v1/conversations/{sessionId}/messages", SESSION_ID)
                .header(IDEMPOTENCY_KEY, "message-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hello\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.version").value(2));

        mvc.perform(post("/api/v1/conversations/{sessionId}/messages", SESSION_ID)
                .header(IDEMPOTENCY_KEY, "message-key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"stale\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.details.expectedVersion").value(1))
                .andExpect(jsonPath("$.details.actualVersion").value(2));
    }

    @Test
    void rejectsStaleConversationVersionForCancellation() throws Exception {
        createConversation();

        mvc.perform(post("/api/v1/conversations/{sessionId}/cancel", SESSION_ID)
                .header(IDEMPOTENCY_KEY, "cancel-stale")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.details.expectedVersion").value(0))
                .andExpect(jsonPath("$.details.actualVersion").value(1));
    }

    @Test
    void cancelsIdempotentlyAndReturnsCancelledStatus() throws Exception {
        createConversation();

        mvc.perform(post("/api/v1/conversations/{sessionId}/cancel", SESSION_ID)
                .header(IDEMPOTENCY_KEY, "cancel-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/conversations/{sessionId}/cancel", SESSION_ID)
                .header(IDEMPOTENCY_KEY, "cancel-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mvc.perform(get("/api/v1/conversations/{sessionId}/events", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event: conversation.cancelled")));
    }

    @Test
    void mapsWorkerFailureThroughManagerErrorHandlerWithoutLeakingRuntimeDetails() throws Exception {
        ConversationService unavailable = new ConversationService(new FakeConversationRuntime(false));
        MockMvc unavailableMvc = MockMvcBuilders.standaloneSetup(new ConversationController(unavailable))
                .setControllerAdvice(new ManagerErrorHandler()).build();

        unavailableMvc.perform(post("/api/v1/conversations")
                .header(IDEMPOTENCY_KEY, "create-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("project-a", "team-a")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("model provider is unavailable"))
                .andExpect(content().string(not(containsString("worker is unavailable"))));
    }

    private void createConversation() throws Exception {
        mvc.perform(post("/api/v1/conversations")
                .header(IDEMPOTENCY_KEY, "create-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("project-a", "team-a")))
                .andExpect(status().isCreated());
    }

    private static String createBody(String project, String team) {
        return createBody(SESSION_ID, project, team);
    }

    private static String createBody(UUID sessionId, String project, String team) {
        return "{\"sessionId\":\"" + sessionId + "\",\"project\":\"" + project
                + "\",\"team\":\"" + team + "\",\"worker\":\"worker-a\",\"task\":\"task-a\"}";
    }
}
