package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.agentspec.AgentSpecRecord;
import io.agentteams.controlplane.agentspec.AgentSpecService;
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
class AgentSpecControllerTest {

    @Mock
    private AgentSpecService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentSpecController(service))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @Test
    void createsAgentSpecWithModelReference() throws Exception {
        UUID id = UUID.randomUUID();
        AgentSpecRecord record = new AgentSpecRecord(id, "analyst", "qwenpaw", "deepseek", "deepseek-chat",
                "research", "RUNNING", "DRAFT", "{\"skillRefs\":[\"search-v1\"]}", Instant.now(), Instant.now(), 1);
        when(service.create(eq("spec-key"), any())).thenReturn(record);

        mockMvc.perform(post("/api/v1/agent-specs")
                        .header("Idempotency-Key", "spec-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"analyst\",\"runtime\":\"qwenpaw\","
                                + "\"modelProvider\":\"deepseek\",\"modelName\":\"deepseek-chat\","
                                + "\"teamRef\":\"research\",\"spec\":{\"skillRefs\":[\"search-v1\"]}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.modelProvider").value("deepseek"))
                .andExpect(jsonPath("$.lifecycleStatus").value("DRAFT"));
    }

    @Test
    void listsAgentSpecs() throws Exception {
        AgentSpecRecord record = new AgentSpecRecord(UUID.randomUUID(), "analyst", "qwenpaw", "deepseek",
                "deepseek-chat", null, "RUNNING", "DRAFT", "{}", Instant.now(), Instant.now(), 1);
        when(service.list()).thenReturn(List.of(record));

        mockMvc.perform(get("/api/v1/agent-specs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("analyst"));
    }

    @Test
    void publishesAndDeactivatesAgentSpecWithIdempotencyKeys() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        AgentSpecRecord published = new AgentSpecRecord(id, "analyst", "qwenpaw", "deepseek", "deepseek-chat",
                null, "RUNNING", "PUBLISHED", "{}", now, now, 2);
        AgentSpecRecord disabled = new AgentSpecRecord(id, "analyst", "qwenpaw", "deepseek", "deepseek-chat",
                null, "RUNNING", "DISABLED", "{}", now, now, 3);
        when(service.publish("publish-key", id)).thenReturn(published);
        when(service.deactivate("deactivate-key", id)).thenReturn(disabled);

        mockMvc.perform(post("/api/v1/agent-specs/{id}/publish", id)
                        .header("Idempotency-Key", "publish-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(post("/api/v1/agent-specs/{id}/deactivate", id)
                        .header("Idempotency-Key", "deactivate-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycleStatus").value("DISABLED"))
                .andExpect(jsonPath("$.version").value(3));

        verify(service).publish("publish-key", id);
        verify(service).deactivate("deactivate-key", id);
    }

    @Test
    void rejectsLifecycleTransitionWithoutIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/agent-specs/{id}/publish", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
