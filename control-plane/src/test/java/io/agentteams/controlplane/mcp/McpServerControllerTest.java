package io.agentteams.controlplane.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.api.ApiErrorHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class McpServerControllerTest {

    @Mock
    private McpServerService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new McpServerController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }

    @Test
    void exposesCrudAndDoesNotReturnCredentialReference() throws Exception {
        UUID id = UUID.randomUUID();
        McpServerRecord server = record(id, "weather", McpHealthStatus.UNKNOWN, 0);
        when(service.create(eq("create-key"), any())).thenReturn(server);
        when(service.get(id)).thenReturn(server);
        when(service.list()).thenReturn(List.of(server));
        when(service.update(eq(id), any())).thenReturn(server);
        when(service.updateHealth(eq(id), any())).thenReturn(record(id, "weather", McpHealthStatus.HEALTHY, 1));

        mockMvc.perform(post("/api/v1/mcp-servers")
                        .header("Idempotency-Key", "create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" +
                                "\"name\":\"weather\",\"transport\":\"SSE\",\"endpoint\":"
                                + "\"https://mcp.example.test/sse\",\"credentialRef\":\"secret/mcp\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.transport").value("SSE"))
                .andExpect(jsonPath("$.credentialConfigured").value(true))
                .andExpect(jsonPath("$.credentialRef").doesNotExist());

        mockMvc.perform(get("/api/v1/mcp-servers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("weather"));
        mockMvc.perform(get("/api/v1/mcp-servers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthStatus").value("UNKNOWN"));
        mockMvc.perform(put("/api/v1/mcp-servers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"weather\",\"transport\":\"SSE\","
                                + "\"endpoint\":\"https://mcp.example.test/sse\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/mcp-servers/{id}/health", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"healthStatus\":\"HEALTHY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthStatus").value("HEALTHY"));
        mockMvc.perform(delete("/api/v1/mcp-servers/{id}", id))
                .andExpect(status().isNoContent());

        verify(service).delete(id);
    }

    @Test
    void requiresIdempotencyKeyForCreate() throws Exception {
        mockMvc.perform(post("/api/v1/mcp-servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"weather\",\"transport\":\"SSE\","
                                + "\"endpoint\":\"https://mcp.example.test/sse\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private static McpServerRecord record(UUID id, String name, McpHealthStatus status, long version) {
        return new McpServerRecord(id, name, McpTransport.SSE, "https://mcp.example.test/sse", "secret/mcp", true,
                status, null, Instant.parse("2026-08-23T00:00:00Z"), Instant.parse("2026-08-23T00:00:00Z"), version);
    }
}
