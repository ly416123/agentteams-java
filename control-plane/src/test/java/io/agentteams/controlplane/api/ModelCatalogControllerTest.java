package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.persistence.ModelProviderRecord;
import io.agentteams.controlplane.persistence.ModelRecord;
import io.agentteams.controlplane.service.ModelCatalogService;
import io.agentteams.controlplane.service.ModelCatalogDependencyException;
import io.agentteams.controlplane.service.ModelProviderConnectionProbe;
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
class ModelCatalogControllerTest {

    @Mock
    private ModelCatalogService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ModelCatalogController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }

    @Test
    void createsProviderWithoutReturningCredentialReference() throws Exception {
        UUID id = UUID.randomUUID();
        ModelProviderRecord provider = new ModelProviderRecord(id, "deepseek", "openai-compatible",
                "https://api.deepseek.com/v1/chat/completions", "secret/deepseek", "{\"region\":\"cn\"}",
                true, Instant.parse("2026-08-23T00:00:00Z"), Instant.parse("2026-08-23T00:00:00Z"), 0);
        when(service.createProvider(eq("provider-key"), any())).thenReturn(provider);

        mockMvc.perform(post("/api/v1/model-providers")
                        .header("Idempotency-Key", "provider-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"deepseek\",\"providerType\":\"openai-compatible\","
                                + "\"endpoint\":\"https://api.deepseek.com/v1/chat/completions\","
                                + "\"credentialRef\":\"secret/deepseek\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.credentialConfigured").value(true))
                .andExpect(jsonPath("$.credentialRef").doesNotExist())
                .andExpect(jsonPath("$.settings").doesNotExist());
    }

    @Test
    void createsAndListsModelsUnderProvider() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        ModelRecord model = new ModelRecord(modelId, providerId, "fast", "deepseek-chat",
                "{\"toolCalling\":true}", true, Instant.now(), Instant.now(), 0);
        when(service.createModel(eq(providerId), eq("model-key"), any())).thenReturn(model);
        when(service.listModels(providerId)).thenReturn(List.of(model));

        mockMvc.perform(post("/api/v1/model-providers/{providerId}/models", providerId)
                        .header("Idempotency-Key", "model-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"fast\",\"modelId\":\"deepseek-chat\","
                                + "\"capabilities\":{\"toolCalling\":true}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(modelId.toString()))
                .andExpect(jsonPath("$.providerId").value(providerId.toString()))
                .andExpect(jsonPath("$.modelId").value("deepseek-chat"));

        mockMvc.perform(get("/api/v1/model-providers/{providerId}/models", providerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("fast"))
                .andExpect(jsonPath("$[0].capabilities").value("{\"toolCalling\":true}"));
    }

    @Test
    void rejectsMissingProviderIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/model-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"deepseek\",\"providerType\":\"openai-compatible\","
                                + "\"endpoint\":\"https://api.deepseek.com/v1/chat/completions\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void returnsStableValidationOnlyConnectionTestClassification() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(service.testProviderConnection(eq(providerId), eq(java.time.Duration.ofSeconds(5))))
                .thenReturn(new ModelProviderConnectionProbe.ProbeResult(
                        ModelProviderConnectionProbe.ProbeResult.Status.NOT_ATTEMPTED,
                        "VALIDATION_ONLY", false, List.of(
                                new ModelProviderConnectionProbe.ProbeResult.Check("URI", "VALID"))));

        mockMvc.perform(post("/api/v1/model-providers/{providerId}/connection-test", providerId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_ATTEMPTED"))
                .andExpect(jsonPath("$.classification").value("VALIDATION_ONLY"))
                .andExpect(jsonPath("$.networkCallAttempted").value(false));
    }

    @Test
    void exposesStableDependencyConflictClassification() throws Exception {
        UUID providerId = UUID.randomUUID();
        doThrow(new ModelCatalogDependencyException("MODEL_PROVIDER_IN_USE", "in use"))
                .when(service).deleteProvider(providerId);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/model-providers/{providerId}", providerId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODEL_PROVIDER_IN_USE"));
    }
}
