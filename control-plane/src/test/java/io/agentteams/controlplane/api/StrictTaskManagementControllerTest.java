package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StrictTaskManagementControllerTest {
    @Test
    void requiresExpectedVersionOnStrictManagementPath() throws Exception {
        MockMvc mvc = mvc(mock(TaskService.class));

        mvc.perform(post("/api/v1/management/tasks/{id}/retry", UUID.randomUUID())
                .header("Idempotency-Key", "retry-key")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void keepsIdempotencyAndVersionOnStrictManagementPath() throws Exception {
        TaskService service = mock(TaskService.class);
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        TaskRecord task = new TaskRecord(id, "Deploy", "description", TaskPhase.QUEUED, 1, "{}", "actor-a",
                "management", null, null, now, now, 2);
        when(service.retry(eq(id), eq(1L), eq("retry-key"), eq("api"), eq("management"))).thenReturn(task);

        mvc(service).perform(post("/api/v1/management/tasks/{id}/retry", id)
                .header("Idempotency-Key", "retry-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"source\":\"management\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("QUEUED"));
    }

    private static MockMvc mvc(TaskService service) {
        return MockMvcBuilders.standaloneSetup(new StrictTaskManagementController(service))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }
}
