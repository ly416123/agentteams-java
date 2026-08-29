package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.persistence.TaskListRecord;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TaskListControllerTest {
    @Test
    void listsFilteredTaskSummariesInCursorEnvelope() throws Exception {
        TaskService service = mock(TaskService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaskController(service), new TaskEventController(service))
                .setControllerAdvice(new ApiErrorHandler()).build();
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        UUID teamId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        TaskListRecord task = new TaskListRecord(UUID.randomUUID(), "Deploy", TaskPhase.QUEUED, 7,
                "tenant-a", "project-a", "team-a", "actor-a", "rest", teamId, workerId, now, now, 3);
        when(service.list(any(), any())).thenReturn(new CursorPage<>(List.of(task), null, false, now));

        mvc.perform(get("/api/v1/tasks").param("phase", "QUEUED").param("actor", "actor-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Deploy"))
                .andExpect(jsonPath("$.items[0].priority").value(7))
                .andExpect(jsonPath("$.items[0].tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.items[0].projectId").value("project-a"))
                .andExpect(jsonPath("$.items[0].team").value("team-a"))
                .andExpect(jsonPath("$.items[0].teamId").value(teamId.toString()))
                .andExpect(jsonPath("$.items[0].workerId").value(workerId.toString()))
                .andExpect(jsonPath("$.items[0].spec").doesNotExist())
                .andExpect(jsonPath("$.items[0].input").doesNotExist())
                .andExpect(jsonPath("$.items[0].version").value(3));
    }
}
