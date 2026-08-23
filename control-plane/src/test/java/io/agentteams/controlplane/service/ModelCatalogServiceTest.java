package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.ModelProviderRecord;
import io.agentteams.controlplane.persistence.ModelRecord;
import java.time.Clock;
import java.time.Duration;
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

    @Mock
    private AuditRecorder auditRecorder;

    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService(persistence, new IdempotencyService(),
                Clock.fixed(NOW, ZoneOffset.UTC), auditRecorder);
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

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().actor()).isEqualTo("api");
        assertThat(audit.getValue().action()).isEqualTo("CREATE_MODEL_PROVIDER");
        assertThat(audit.getValue().resourceType()).isEqualTo("model_provider");
        assertThat(audit.getValue().resourceId()).isEqualTo(provider.id().toString());
        assertThat(audit.getValue().attributes()).containsEntry("result", "SUCCESS");
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

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("CREATE_MODEL");
        assertThat(audit.getValue().resourceType()).isEqualTo("model");
        assertThat(audit.getValue().resourceId()).isEqualTo(model.id().toString());
        assertThat(audit.getValue().attributes()).containsEntry("result", "SUCCESS");
    }

    @Test
    void auditFailureDoesNotChangeSuccessfulCatalogWrite() {
        when(persistence.createModelProvider(any(ModelProviderRecord.class), eq("provider-key"), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new IllegalStateException("audit unavailable")).when(auditRecorder).record(any());

        ModelProviderRecord provider = service.createProvider("provider-key",
                new ModelCatalogService.ProviderInput("deepseek", "openai-compatible",
                        "https://api.deepseek.com/v1/chat/completions", null, "{}", true));

        assertThat(provider.name()).isEqualTo("deepseek");
        verify(persistence).createModelProvider(any(ModelProviderRecord.class), eq("provider-key"), any());
    }

    @Test
    void recordsFailureResultWhenCatalogWriteFails() {
        when(persistence.createModelProvider(any(ModelProviderRecord.class), eq("provider-key"), any()))
                .thenThrow(new IllegalStateException("catalog unavailable"));

        assertThatThrownBy(() -> service.createProvider("provider-key",
                new ModelCatalogService.ProviderInput("deepseek", "openai-compatible",
                        "https://api.deepseek.com/v1/chat/completions", null, "{}", true)))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("CREATE_MODEL_PROVIDER");
        assertThat(audit.getValue().attributes()).containsEntry("result", "FAILURE");
        assertThat(audit.getValue().resourceId()).isNotBlank();
    }

    @Test
    void defaultConnectionProbeOnlyClassifiesWithoutNetworkCall() {
        UUID providerId = UUID.randomUUID();
        when(persistence.findModelProvider(providerId)).thenReturn(java.util.Optional.of(
                new ModelProviderRecord(providerId, "deepseek", "openai-compatible",
                        "https://api.deepseek.com/v1", "secret/deepseek", "{}", true, NOW, NOW, 0)));

        ModelProviderConnectionProbe.ProbeResult result = service.testProviderConnection(providerId,
                Duration.ofSeconds(5));

        assertThat(result.status()).isEqualTo(ModelProviderConnectionProbe.ProbeResult.Status.NOT_ATTEMPTED);
        assertThat(result.classification()).isEqualTo("VALIDATION_ONLY");
        assertThat(result.networkCallAttempted()).isFalse();
        assertThat(result.checks()).extracting(ModelProviderConnectionProbe.ProbeResult.Check::name)
                .containsExactly("URI", "CREDENTIAL_REFERENCE", "TIMEOUT");
        assertThat(result.checks()).filteredOn(check -> check.name().equals("CREDENTIAL_REFERENCE"))
                .extracting(ModelProviderConnectionProbe.ProbeResult.Check::status)
                .containsExactly("VALIDATION_ONLY");
    }

    @Test
    void connectionProbeReceivesOnlyCredentialReferenceAndCanBeReplaced() {
        ModelProviderConnectionProbe probe = request -> {
            assertThat(request.credentialReference()).isEqualTo("secret/deepseek");
            return new ModelProviderConnectionProbe.ProbeResult(
                    ModelProviderConnectionProbe.ProbeResult.Status.CONNECTED, "CUSTOM", true, java.util.List.of());
        };
        ModelCatalogService custom = new ModelCatalogService(persistence, new IdempotencyService(),
                Clock.fixed(NOW, ZoneOffset.UTC), auditRecorder, probe);
        UUID providerId = UUID.randomUUID();
        when(persistence.findModelProvider(providerId)).thenReturn(java.util.Optional.of(
                new ModelProviderRecord(providerId, "deepseek", "openai-compatible",
                        "https://api.deepseek.com/v1", "secret/deepseek", "{}", true, NOW, NOW, 0)));

        assertThat(custom.testProviderConnection(providerId, Duration.ofSeconds(1)).classification())
                .isEqualTo("CUSTOM");
    }

    @Test
    void lifecycleDependencyClassificationIsPropagated() {
        UUID providerId = UUID.randomUUID();
        when(persistence.updateModelProviderEnabled(providerId, false, NOW))
                .thenThrow(new ModelCatalogDependencyException("MODEL_PROVIDER_IN_USE", "in use"));

        assertThatThrownBy(() -> service.setProviderEnabled(providerId, false))
                .isInstanceOf(ModelCatalogDependencyException.class)
                .extracting(error -> ((ModelCatalogDependencyException) error).code())
                .isEqualTo("MODEL_PROVIDER_IN_USE");
    }
}
