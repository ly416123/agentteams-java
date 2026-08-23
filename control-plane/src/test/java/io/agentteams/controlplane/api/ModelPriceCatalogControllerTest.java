package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.persistence.ModelPriceRecord;
import io.agentteams.controlplane.service.ModelPriceCatalogService;
import java.math.BigDecimal;
import java.time.Instant;
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
class ModelPriceCatalogControllerTest {

    @Mock
    private ModelPriceCatalogService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ModelPriceCatalogController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }

    @Test
    void createsPriceWithoutAnySecretBearingFields() throws Exception {
        UUID id = UUID.randomUUID();
        Instant effectiveFrom = Instant.parse("2026-08-23T00:00:00Z");
        ModelPriceRecord price = new ModelPriceRecord(id, "tenant-a", "project-a", "openai", "gpt-4o",
                "USD", new BigDecimal("2.5"), new BigDecimal("10"), effectiveFrom, null, "ACTIVE",
                effectiveFrom, effectiveFrom, 0, "alice", "alice");
        when(service.createPrice(eq("price-key"), any())).thenReturn(price);

        mockMvc.perform(post("/api/v1/model-prices")
                        .header("Idempotency-Key", "price-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"provider\":\"openai\",\"model\":\"gpt-4o\","
                                + "\"currency\":\"USD\",\"inputPricePerMillionTokens\":2.5,"
                                + "\"outputPricePerMillionTokens\":10,"
                                + "\"effectiveFrom\":\"2026-08-23T00:00:00Z\",\"lifecycleStatus\":\"ACTIVE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.provider").value("openai"))
                .andExpect(jsonPath("$.inputPricePerMillionTokens").value(2.5))
                .andExpect(jsonPath("$.createdBy").value("alice"))
                .andExpect(jsonPath("$.credentialRef").doesNotExist());
    }
}
