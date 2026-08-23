package io.agentteams.controlplane.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import io.agentteams.controlplane.api.ApiErrorHandler;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AuditEventsControllerTest {
    @Mock
    private JdbcAuditQueryService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuditEventsController(service))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @Test
    void listsFilteredAuditEventsWithoutWriteRoutes() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.find(any())).thenReturn(List.of(new AuditEvent(id, "operator", "worker.update", "worker",
                "worker-1", Map.of("model", "qwen"), Instant.parse("2026-08-23T00:00:00Z"))));

        mockMvc.perform(get("/api/v1/audit-events")
                        .param("resourceType", "worker")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].action").value("worker.update"))
                .andExpect(jsonPath("$[0].attributes.model").value("qwen"));
    }

    @Test
    void rejectsAnUnboundedLimit() throws Exception {
        mockMvc.perform(get("/api/v1/audit-events").param("limit", "101"))
                .andExpect(status().isBadRequest());
    }
}
