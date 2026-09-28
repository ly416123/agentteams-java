package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.agentteams.controlplane.matrix.MatrixRoomBinding;
import io.agentteams.controlplane.matrix.MatrixRoomBindingService;
import io.agentteams.controlplane.matrix.MatrixRoomBindingStatus;
import io.agentteams.controlplane.matrix.MatrixRoomContextType;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceAuthorizationService;
import io.agentteams.controlplane.security.ResourceAction;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class MatrixRoomBindingControllerTest {
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID BINDING_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Principal ADMIN = new Principal("admin",
            new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of());
    private static final ExecutionContext CONTEXT =
            new ExecutionContext("org-a", "tenant-a", "project-a", "team-a", "admin");

    private MatrixRoomBindingService service;
    private ExecutionContextResolver contexts;
    private ResourceAuthorizationService authorization;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(MatrixRoomBindingService.class);
        contexts = mock(ExecutionContextResolver.class);
        authorization = mock(ResourceAuthorizationService.class);
        PrincipalContext.set(ADMIN);
        when(contexts.resolve(ADMIN)).thenReturn(CONTEXT);
        mvc = standaloneSetup(new MatrixRoomBindingController(service, contexts, authorization))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void createsBindingOnlyAfterAdminScopeCheck() throws Exception {
        MatrixRoomBinding binding = binding(MatrixRoomBindingStatus.ACTIVE, 0);
        when(service.bind(eq("org-a"), eq("tenant-a"), eq("!room:example.org"),
                eq(MatrixRoomContextType.CONVERSATION), eq(CONVERSATION_ID), eq(PROJECT_ID), eq(TEAM_ID), eq("admin")))
                .thenReturn(binding);

        mvc.perform(post("/api/v1/matrix/rooms/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("CONVERSATION", CONVERSATION_ID, 0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(BINDING_ID.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0));

        verify(authorization).require(eq(ResourceAction.TEAM_WRITE), any(io.agentteams.controlplane.security.ResourceRef.class));
        verify(service).bind(eq("org-a"), eq("tenant-a"), eq("!room:example.org"),
                eq(MatrixRoomContextType.CONVERSATION), eq(CONVERSATION_ID), eq(PROJECT_ID), eq(TEAM_ID), eq("admin"));
    }

    @Test
    void listsBindingsByContextWithinAuthenticatedTenant() throws Exception {
        when(service.list("tenant-a", MatrixRoomContextType.CONVERSATION, CONVERSATION_ID))
                .thenReturn(List.of(binding(MatrixRoomBindingStatus.ACTIVE, 2)));

        mvc.perform(get("/api/v1/matrix/rooms/bindings")
                        .param("contextType", "CONVERSATION")
                        .param("contextId", CONVERSATION_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].primaryContextType").value("CONVERSATION"))
                .andExpect(jsonPath("$[0].roomId").value("!room:example.org"));

        verify(service).list("tenant-a", MatrixRoomContextType.CONVERSATION, CONVERSATION_ID);
    }

    @Test
    void migratesAndRevokesBindingsWithExpectedVersion() throws Exception {
        MatrixRoomBinding migrated = binding(MatrixRoomBindingStatus.ACTIVE, 1);
        when(service.migrate(eq("tenant-a"), eq(BINDING_ID), eq(MatrixRoomContextType.TASK), eq(TEAM_ID),
                eq(PROJECT_ID), eq(TEAM_ID), eq(0L), eq("admin"))).thenReturn(migrated);
        when(service.revoke(eq("tenant-a"), eq(BINDING_ID), eq(1L), eq("admin")))
                .thenReturn(binding(MatrixRoomBindingStatus.REVOKED, 2));

        mvc.perform(post("/api/v1/matrix/rooms/bindings/{id}/migrate", BINDING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("TASK", TEAM_ID, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(post("/api/v1/matrix/rooms/bindings/{id}/revoke", BINDING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    void rejectsNonAdminWithoutCallingBindingService() throws Exception {
        doThrow(new io.agentteams.controlplane.security.AuthorizationException("denied"))
                .when(authorization).require(eq(ResourceAction.TEAM_WRITE), any(io.agentteams.controlplane.security.ResourceRef.class));

        mvc.perform(post("/api/v1/matrix/rooms/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("CONVERSATION", CONVERSATION_ID, 0)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(service);
    }

    private static String request(String type, UUID contextId, long expectedVersion) {
        return "{\"roomId\":\"!room:example.org\",\"contextType\":\"" + type
                + "\",\"contextId\":\"" + contextId + "\",\"projectId\":\"" + PROJECT_ID
                + "\",\"teamId\":\"" + TEAM_ID + "\",\"expectedVersion\":" + expectedVersion + "}";
    }

    private static MatrixRoomBinding binding(MatrixRoomBindingStatus status, long version) {
        Instant now = Instant.parse("2026-09-28T05:00:00Z");
        return new MatrixRoomBinding(BINDING_ID, "org-a", "tenant-a", "!room:example.org",
                MatrixRoomContextType.CONVERSATION, CONVERSATION_ID, PROJECT_ID, TEAM_ID, status, "admin", now, now,
                version);
    }
}
