package io.agentteams.controlplane.task;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TaskStateConsistencyControllerTest {
    @Test
    void exposesOpenFindingsToInternalConsumers() throws Exception {
        TaskStateConsistencyService service = mock(TaskStateConsistencyService.class);
        UUID taskId = UUID.randomUUID();
        when(service.findOpenIssues(10)).thenReturn(List.of(new TaskStateConsistencyIssueRecord(
                UUID.randomUUID(), taskId, UUID.randomUUID(), "org-1", "tenant-1", "RESULT_MANIFEST_MISSING",
                "SUCCEEDED", "SUCCEEDED", null, "terminal run has no result manifest", "OPEN", 2,
                Instant.parse("2026-08-30T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"), null)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaskStateConsistencyController(service, "secret")).build();

        mvc.perform(get("/internal/v1/task-state-consistency/issues")
                        .param("limit", "10")
                        .header(TaskStateConsistencyController.TOKEN_HEADER, "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("RESULT_MANIFEST_MISSING"))
                .andExpect(jsonPath("$[0].occurrences").value(2));
        verify(service).findOpenIssues(eq(10));
    }

    @Test
    void rejectsRequestsWithoutInternalToken() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new TaskStateConsistencyController(mock(TaskStateConsistencyService.class), "secret")).build();

        mvc.perform(get("/internal/v1/task-state-consistency/issues"))
                .andExpect(status().isForbidden());
    }
}
