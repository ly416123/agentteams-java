package io.agentteams.controlplane.agentspec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentSpecServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Mock
    private AgentSpecRepository repository;

    @Mock
    private AgentSpecModelCatalog modelCatalog;

    private AgentSpecService service;

    @BeforeEach
    void setUp() {
        service = new AgentSpecService(repository, modelCatalog, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsVersionedDraftAndNormalizesState() {
        stubActiveModel();
        when(repository.findIdempotency(any())).thenReturn(Optional.empty());
        when(repository.insertIdempotency(any())).thenReturn(true);
        AgentSpecRecord record = service.create("spec-key", new AgentSpecService.Input(
                " analyst ", "qwenpaw", "deepseek", "deepseek-chat", "research", "running",
                "{\"skillRefs\":[\"search-v1\"]}"));

        assertThat(record.name()).isEqualTo("analyst");
        assertThat(record.desiredState()).isEqualTo("RUNNING");
        assertThat(record.lifecycleStatus()).isEqualTo("DRAFT");
        assertThat(record.version()).isEqualTo(1);
        assertThat(record.createdAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsUnsupportedDesiredState() {
        assertThatThrownBy(() -> service.create("spec-key", new AgentSpecService.Input(
                "analyst", "qwenpaw", "deepseek", "deepseek-chat", null, "paused", "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("desiredState must be RUNNING or STOPPED");
    }

    @Test
    void rejectsMalformedOrDuplicateRuntimeReferencesBeforeCreatingSpec() {
        assertThatThrownBy(() -> service.create("bad-spec", new AgentSpecService.Input(
                "analyst", "qwenpaw", "deepseek", "deepseek-chat", null, "RUNNING",
                "{\"skillRefs\":[\"search-v1\",\"search-v1\"]}")))
                .hasMessageContaining("unique non-blank references");

        assertThatThrownBy(() -> service.create("bad-spec-json", new AgentSpecService.Input(
                "analyst", "qwenpaw", "deepseek", "deepseek-chat", null, "RUNNING", "[]")))
                .hasMessage("spec must be a JSON object");
        verify(repository, never()).insert(any());
    }

    @Test
    void rejectsUnknownProviderBeforeCreatingSpec() {
        when(modelCatalog.findProviderByName("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("spec-key", new AgentSpecService.Input(
                "analyst", "qwenpaw", "missing", "deepseek-chat", null, "RUNNING", "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("model provider does not exist: missing");

        verify(repository, never()).insert(any());
    }

    @Test
    void rejectsDisabledProviderAndModel() {
        UUID providerId = UUID.randomUUID();
        when(modelCatalog.findProviderByName("deepseek"))
                .thenReturn(Optional.of(new AgentSpecModelCatalog.ProviderReference(providerId, false)));

        assertThatThrownBy(() -> service.create("provider-disabled", new AgentSpecService.Input(
                "analyst", "qwenpaw", "deepseek", "deepseek-chat", null, "RUNNING", "{}")))
                .hasMessage("model provider is disabled: deepseek");

        when(modelCatalog.findProviderByName("deepseek"))
                .thenReturn(Optional.of(new AgentSpecModelCatalog.ProviderReference(providerId, true)));
        when(modelCatalog.findModelById(providerId, "deepseek-chat"))
                .thenReturn(Optional.of(new AgentSpecModelCatalog.ModelReference(false)));

        assertThatThrownBy(() -> service.create("model-disabled", new AgentSpecService.Input(
                "analyst", "qwenpaw", "deepseek", "deepseek-chat", null, "RUNNING", "{}")))
                .hasMessage("model is disabled for provider: deepseek/deepseek-chat");
    }

    @Test
    void rejectsModelNotOwnedByProvider() {
        UUID providerId = UUID.randomUUID();
        when(modelCatalog.findProviderByName("deepseek"))
                .thenReturn(Optional.of(new AgentSpecModelCatalog.ProviderReference(providerId, true)));
        when(modelCatalog.findModelById(providerId, "other-model")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("model-key", new AgentSpecService.Input(
                "analyst", "qwenpaw", "deepseek", "other-model", null, "RUNNING", "{}")))
                .hasMessage("model does not exist for provider: deepseek/other-model");
    }

    @Test
    void returnsIdempotentResourceForSameRequest() {
        UUID id = UUID.randomUUID();
        AgentSpecService.Input input = new AgentSpecService.Input(
                "analyst", "qwenpaw", "deepseek", "deepseek-chat", null, "RUNNING", "{}");
        AgentSpecRecord existing = new AgentSpecRecord(id, "analyst", "qwenpaw", "deepseek", "deepseek-chat",
                null, "RUNNING", "DRAFT", "{}", NOW, NOW, 1);
        when(repository.findIdempotency("spec-key")).thenReturn(Optional.of(
                new AgentSpecRepository.IdempotencyRecord("spec-key", sha(input), id, NOW)));
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThat(service.create("spec-key", input)).isEqualTo(existing);
    }

    @Test
    void listsRepositoryRecords() {
        when(repository.findAll()).thenReturn(List.of());
        assertThat(service.list()).isEmpty();
    }

    @Test
    void publishesDraftAndDeactivatesPublishedSpecWithVersionedTransitions() {
        AgentSpecRecord draft = record("DRAFT", 1);
        when(repository.findIdempotency("publish-key")).thenReturn(Optional.empty());
        when(repository.insertIdempotency(any())).thenReturn(true);
        when(repository.findById(draft.id())).thenReturn(Optional.of(draft));

        AgentSpecRecord published = service.publish("publish-key", draft.id());

        assertThat(published.lifecycleStatus()).isEqualTo("PUBLISHED");
        assertThat(published.version()).isEqualTo(2);
        verify(repository).updateLifecycle(published, 1);

        AgentSpecRecord publishedAgain = published;
        when(repository.findIdempotency("deactivate-key")).thenReturn(Optional.empty());
        when(repository.findById(draft.id())).thenReturn(Optional.of(publishedAgain));
        AgentSpecRecord disabled = service.deactivate("deactivate-key", draft.id());

        assertThat(disabled.lifecycleStatus()).isEqualTo("DISABLED");
        assertThat(disabled.version()).isEqualTo(3);
        verify(repository).updateLifecycle(disabled, 2);
    }

    @Test
    void publishesOnlyWhenInjectedReferenceValidatorReturnsValid() {
        AgentSpecRecord draft = record("DRAFT", 1);
        when(repository.findIdempotency("reference-key")).thenReturn(Optional.empty());
        when(repository.findById(draft.id())).thenReturn(Optional.of(draft));
        AgentSpecReferenceValidationResult invalid = new AgentSpecReferenceValidationResult(
                AgentSpecReferenceValidationResult.Category.PROJECT_NOT_VISIBLE,
                List.of(new AgentSpecReferenceValidationResult.Violation(
                        new AgentSpecReference(AgentSpecReferenceType.SKILL, "private"),
                        AgentSpecReferenceValidationResult.Category.PROJECT_NOT_VISIBLE,
                        "outside project")));
        AgentSpecService secured = new AgentSpecService(repository, modelCatalog, Clock.fixed(NOW, ZoneOffset.UTC),
                new AgentSpecSchemaValidator(), request -> invalid);

        assertThatThrownBy(() -> secured.publish("reference-key", draft.id()))
                .isInstanceOf(AgentSpecReferenceValidationException.class)
                .extracting(error -> ((AgentSpecReferenceValidationException) error).category())
                .isEqualTo(AgentSpecReferenceValidationResult.Category.PROJECT_NOT_VISIBLE);
        verify(repository, never()).updateLifecycle(any(), any(Long.class));
    }

    @Test
    void repeatsPublishWithoutChangingVersionAndRejectsIllegalTransition() {
        UUID id = UUID.randomUUID();
        AgentSpecRecord published = record("PUBLISHED", 2);
        when(repository.findIdempotency("same-key")).thenReturn(Optional.of(
                new AgentSpecRepository.IdempotencyRecord("same-key", transitionSha("PUBLISH", id), id, NOW)));
        when(repository.findById(id)).thenReturn(Optional.of(published));

        assertThat(service.publish("same-key", id)).isSameAs(published);

        when(repository.findIdempotency("bad-key")).thenReturn(Optional.empty());
        when(repository.findById(id)).thenReturn(Optional.of(published));
        assertThatThrownBy(() -> service.publish("bad-key", id))
                .hasMessage("cannot publish agent spec from lifecycle status: PUBLISHED");
    }

    private void stubActiveModel() {
        UUID providerId = UUID.randomUUID();
        when(modelCatalog.findProviderByName("deepseek"))
                .thenReturn(Optional.of(new AgentSpecModelCatalog.ProviderReference(providerId, true)));
        when(modelCatalog.findModelById(providerId, "deepseek-chat"))
                .thenReturn(Optional.of(new AgentSpecModelCatalog.ModelReference(true)));
    }

    private static AgentSpecRecord record(String lifecycle, long version) {
        UUID id = UUID.randomUUID();
        return new AgentSpecRecord(id, "analyst", "qwenpaw", "deepseek", "deepseek-chat", null,
                "RUNNING", lifecycle, "{}", NOW, NOW, version);
    }

    private static String sha(AgentSpecService.Input input) {
        try {
            String value = String.join("\u0000", input.name(), input.runtime(), input.modelProvider(),
                    input.modelName(), "", input.desiredState(), input.specJson());
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    private static String transitionSha(String operation, UUID id) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((operation + "\u0000" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
