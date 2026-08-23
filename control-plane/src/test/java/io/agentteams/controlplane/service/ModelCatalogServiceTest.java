package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.ModelProviderRecord;
import io.agentteams.controlplane.persistence.ModelRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModelCatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Mock
    private FoundationPersistenceService persistence;

    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService(persistence, new IdempotencyService(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void normalizesProviderInputAndPersistsOnlyCredentialReference() {
        when(persistence.createModelProvider(any(ModelProviderRecord.class), eq("provider-key"), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ModelProviderRecord provider = service.createProvider("provider-key",
                new ModelCatalogService.ProviderInput(" deepseek ", "openai-compatible",
                        "https://api.deepseek.com/v1/chat/completions", " secret/deepseek ",
                        "{\"region\":\"cn\"}", true));

        assertThat(provider.name()).isEqualTo("deepseek");
        assertThat(provider.credentialRef()).isEqualTo("secret/deepseek");
        assertThat(provider.settingsJson()).isEqualTo("{\"region\":\"cn\"}");
        assertThat(provider.createdAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsNonAbsoluteProviderEndpointBeforePersistence() {
        assertThatThrownBy(() -> service.createProvider("provider-key",
                new ModelCatalogService.ProviderInput("deepseek", "openai-compatible", "/v1/chat", null,
                        "{}", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("endpoint must be an absolute URI");

        verify(persistence, never()).createModelProvider(any(), any(), any());
    }

    @Test
    void createsModelWithProviderReferenceAndNormalizedCapabilities() {
        UUID providerId = UUID.randomUUID();
        when(persistence.createModel(any(ModelRecord.class), eq("model-key"), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ModelRecord model = service.createModel(providerId, "model-key",
                new ModelCatalogService.ModelInput("fast", "deepseek-chat", "{\"toolCalling\":true}", true));

        assertThat(model.providerId()).isEqualTo(providerId);
        assertThat(model.modelId()).isEqualTo("deepseek-chat");
        assertThat(model.capabilitiesJson()).isEqualTo("{\"toolCalling\":true}");
        ArgumentCaptor<ModelRecord> captured = ArgumentCaptor.forClass(ModelRecord.class);
        verify(persistence).createModel(captured.capture(), eq("model-key"), any());
        assertThat(captured.getValue().createdAt()).isEqualTo(NOW);
    }
}
