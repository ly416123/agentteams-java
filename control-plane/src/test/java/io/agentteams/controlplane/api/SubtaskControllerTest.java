package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.controlplane.task.SubtaskService;
import io.agentteams.controlplane.task.TaskTreeNode;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class SubtaskControllerTest {
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final String SPEC_JSON =
            "{\"scope\":{\"tenant\":\"tenant-1\",\"project\":\"project-1\",\"team\":\"team-1\"}}";
    private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");

    private final TaskService tasks = mock(TaskService.class);
    private final SubtaskService subtasks = mock(SubtaskService.class);
    private final FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(tasks.get(TASK_ID)).thenReturn(taskRecord());
        when(persistence.inTransaction(any())).thenReturn(List.of());
        when(subtasks.listProjection(TASK_ID)).thenReturn(List.of());
        mvc = standaloneSetup(new SubtaskController(tasks, subtasks, persistence))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void putSubtasksRequiresIdempotencyKey() throws Exception {
        mvc.perform(put("/api/v1/tasks/{taskId}/subtasks", TASK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subtasks\":[{\"subtaskId\":\"" + UUID.randomUUID()
                                + "\",\"title\":\"t\",\"sequence\":1,\"dependencyIds\":[]}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putSubtasksValidatesSizeAndStatus() throws Exception {
        when(subtasks.plan(eq(TASK_ID), anyList()))
                .thenThrow(new IllegalArgumentException("subtasks size must be between 1 and 20"));
        String oversized = "{\"subtasks\":[";
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            items.add("{\"subtaskId\":\"" + UUID.randomUUID() + "\",\"title\":\"t" + i
                    + "\",\"sequence\":" + (i + 1) + ",\"dependencyIds\":[]}");
        }
        oversized += String.join(",", items) + "]}";
        mvc.perform(put("/api/v1/tasks/{taskId}/subtasks", TASK_ID)
                        .header("Idempotency-Key", "k-oversize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isBadRequest());

        when(subtasks.updateStatus(eq(TASK_ID), any(), eq("BLOCKED"), any()))
                .thenThrow(new IllegalArgumentException("status must be one of [RUNNING, SUCCEEDED, FAILED, CANCELLED]"));
        mvc.perform(put("/api/v1/tasks/{taskId}/subtasks/{subtaskId}/status", TASK_ID, UUID.randomUUID())
                        .header("Idempotency-Key", "k-blocked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putSubtasksReturnsNodesAndUnknownTaskIs404() throws Exception {
        UUID subtaskId = UUID.randomUUID();
        when(subtasks.plan(eq(TASK_ID), anyList()))
                .thenReturn(List.of(new TaskTreeNode(subtaskId, TASK_ID, 1, "PENDING", List.of(), NOW)));

        mvc.perform(put("/api/v1/tasks/{taskId}/subtasks", TASK_ID)
                        .header("Idempotency-Key", "k-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subtasks\":[{\"subtaskId\":\"" + subtaskId
                                + "\",\"title\":\"抓取邮件\",\"sequence\":1,\"dependencyIds\":[]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].taskId").value(subtaskId.toString()))
                .andExpect(jsonPath("$.nodes[0].status").value("PENDING"));

        UUID unknown = UUID.randomUUID();
        when(tasks.get(unknown)).thenThrow(new ResourceNotFoundException("task", unknown));
        mvc.perform(put("/api/v1/tasks/{taskId}/subtasks", unknown)
                        .header("Idempotency-Key", "k-404")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subtasks\":[{\"subtaskId\":\"" + UUID.randomUUID()
                                + "\",\"title\":\"t\",\"sequence\":1,\"dependencyIds\":[]}]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void putSubtaskStatusForwardsToService() throws Exception {
        UUID subtaskId = UUID.randomUUID();
        when(subtasks.updateStatus(TASK_ID, subtaskId, "RUNNING", null))
                .thenReturn(new TaskTreeNode(subtaskId, TASK_ID, 1, "RUNNING", List.of(), NOW));

        mvc.perform(put("/api/v1/tasks/{taskId}/subtasks/{subtaskId}/status", TASK_ID, subtaskId)
                        .header("Idempotency-Key", "k-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RUNNING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.taskId").value(subtaskId.toString()));
    }

    @Test
    void putSubtasksRejectsOutOfScopeCallerWith403() throws Exception {
        PrincipalContext.set(new Principal("user-9",
                new AuthorizationService.Scope("tenant-9", "project-9", "team-9"), Set.of()));

        mvc.perform(put("/api/v1/tasks/{taskId}/subtasks", TASK_ID)
                        .header("Idempotency-Key", "k-403")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subtasks\":[{\"subtaskId\":\"" + UUID.randomUUID()
                                + "\",\"title\":\"t\",\"sequence\":1,\"dependencyIds\":[]}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listMergesProjectionWithRealPhaseAndOrphanFallback() throws Exception {
        UUID projected = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        when(subtasks.listProjection(TASK_ID)).thenReturn(List.of(
                new TaskTreeNode(projected, TASK_ID, 1, "RUNNING", List.of(), NOW)));
        when(persistence.inTransaction(any())).thenReturn(List.of(
                childRecord(projected, TaskPhase.RUNNING), childRecord(orphan, TaskPhase.CANCELLED)));

        mvc.perform(get("/api/v1/tasks/{taskId}/subtasks", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subtaskId").value(projected.toString()))
                .andExpect(jsonPath("$[0].status").value("RUNNING"))
                .andExpect(jsonPath("$[0].phase").value("RUNNING"))
                .andExpect(jsonPath("$[1].subtaskId").value(orphan.toString()))
                .andExpect(jsonPath("$[1].status").value("ORPHANED"))
                .andExpect(jsonPath("$[1].phase").value("CANCELLED"));
    }

    @Test
    void listReturns404ForUnknownTask() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(tasks.get(unknown)).thenThrow(new ResourceNotFoundException("task", unknown));
        mvc.perform(get("/api/v1/tasks/{taskId}/subtasks", unknown))
                .andExpect(status().isNotFound());
    }

    private static TaskRecord childRecord(UUID id, TaskPhase phase) {
        return new TaskRecord(id, "child", "描述", phase, 0, SPEC_JSON,
                "alice", "test", null, null, NOW, NOW, 0,
                "NORMAL", "ACTIVE", null, null, TASK_ID, "SUBTASK");
    }

    private static TaskRecord taskRecord() {
        return new TaskRecord(TASK_ID, "主任务", "描述", TaskPhase.RUNNING, 5, SPEC_JSON,
                "user-1", "api", null, null, NOW, NOW, 1L);
    }
}
