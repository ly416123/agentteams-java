package io.agentteams.controlplane.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerTemplateServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
    private static final Principal PRINCIPAL = new Principal("alice",
            new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of("template:write"));
    private ResourceScopeRepository scopes;

    @BeforeEach
    void setPrincipal() {
        scopes = mock(ResourceScopeRepository.class);
        PrincipalContext.set(PRINCIPAL);
    }

    @AfterEach
    void clearPrincipal() { PrincipalContext.clear(); }

    @Test
    void createsCanonicalRevisionAndDigest() {
        WorkerTemplateRepository repository = mock(WorkerTemplateRepository.class);
        UUID templateId = UUID.randomUUID();
        when(repository.nextRevision(templateId)).thenReturn(1L);
        when(repository.findTemplate(templateId)).thenReturn(Optional.of(template(templateId)));
        when(repository.createRevision(eq(templateId), eq(1L), eq("{\"modelName\":\"qwen\",\"runtime\":\"java\"}"),
                any(), eq("alice"), eq(NOW), eq("revision-key")))
                .thenAnswer(invocation -> revision(templateId, 1, invocation.getArgument(2),
                        invocation.getArgument(3)));
        WorkerTemplateService service = new WorkerTemplateService(repository, mock(TemplateInstanceProvisioner.class),
                scopes, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        WorkerTemplateRevision created = service.createRevision(templateId,
                "{\"runtime\":\"java\",\"modelName\":\"qwen\"}", "alice", "revision-key");

        assertThat(created.specJson()).isEqualTo("{\"modelName\":\"qwen\",\"runtime\":\"java\"}");
        assertThat(created.digest()).hasSize(64);
    }

    @Test
    void cannotPublishARevisionFromInvalidState() {
        WorkerTemplateRepository repository = mock(WorkerTemplateRepository.class);
        UUID templateId = UUID.randomUUID();
        WorkerTemplateRevision published = revision(templateId, 1, "{}", "digest", TemplateStatus.PUBLISHED);
        when(repository.findTemplate(templateId)).thenReturn(Optional.of(template(templateId)));
        when(repository.findRevision(templateId, 1)).thenReturn(Optional.of(published));

        assertThatThrownBy(() -> new WorkerTemplateService(repository, mock(TemplateInstanceProvisioner.class), scopes,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)).publish(templateId, 1, 1, "publish-key"))
                .isInstanceOf(TemplateConflictException.class).hasMessageContaining("cannot be published");
        verify(repository, never()).publish(any(), any(Long.class), any(Long.class), any());
    }

    @Test
    void repeatedInstantiationReturnsTheExistingInstance() {
        WorkerTemplateRepository repository = mock(WorkerTemplateRepository.class);
        TemplateInstanceProvisioner provisioner = mock(TemplateInstanceProvisioner.class);
        UUID templateId = UUID.randomUUID();
        WorkerTemplateRevision published = revision(templateId, 3, "{}", "digest", TemplateStatus.PUBLISHED);
        WorkerTemplateInstance existing = new WorkerTemplateInstance(UUID.randomUUID(), templateId, 3,
                UUID.randomUUID(), UUID.randomUUID(), "SUCCEEDED", 3, "instance-key", "hash", NOW, NOW, 1);
        when(repository.findTemplate(templateId)).thenReturn(Optional.of(template(templateId)));
        when(repository.findRevision(templateId, 3)).thenReturn(Optional.of(published));
        when(repository.findInstanceByIdempotency(templateId, "instance-key")).thenReturn(Optional.of(existing));

        WorkerTemplateInstance result = new WorkerTemplateService(repository, provisioner, scopes,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)).instantiate(templateId, 3, "instance-key");

        assertThat(result).isSameAs(existing);
        verify(provisioner, never()).provision(any(), any(), any());
    }

    @Test
    void upgradesOnlyToANewerPublishedRevision() {
        WorkerTemplateRepository repository = mock(WorkerTemplateRepository.class);
        TemplateInstanceProvisioner provisioner = mock(TemplateInstanceProvisioner.class);
        UUID templateId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        WorkerTemplateInstance current = new WorkerTemplateInstance(instanceId, templateId, 1,
                UUID.randomUUID(), UUID.randomUUID(), "SUCCEEDED", 1, "instance-key", "hash", NOW, NOW, 1);
        WorkerTemplateRevision target = revision(templateId, 2, "{}", "digest-2", TemplateStatus.PUBLISHED);
        when(repository.findTemplate(templateId)).thenReturn(Optional.of(template(templateId)));
        when(repository.findInstance(templateId, instanceId)).thenReturn(Optional.of(current));
        when(repository.findRevision(templateId, 2)).thenReturn(Optional.of(target));
        when(provisioner.upgrade(current, target, "upgrade-key"))
                .thenReturn(new TemplateInstanceProvisioner.ProvisionedInstance(UUID.randomUUID(), UUID.randomUUID()));
        when(repository.upgradeInstance(any(), eq(1L), eq(2L), eq("upgrade-key"), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkerTemplateInstance upgraded = new WorkerTemplateService(repository, provisioner, scopes,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC)).upgrade(templateId, instanceId, 2,
                        "upgrade-key");

        assertThat(upgraded.currentTemplateRevision()).isEqualTo(2);
        assertThat(upgraded.status()).isEqualTo("SUCCEEDED");
    }

    private static WorkerTemplate template(UUID id) {
        return new WorkerTemplate(id, "tenant-a", "project-a", "demo", "Demo", null, 0, NOW, NOW, 0);
    }

    private static WorkerTemplateRevision revision(UUID templateId, long revision, String json, String digest) {
        return revision(templateId, revision, json, digest, TemplateStatus.DRAFT);
    }

    private static WorkerTemplateRevision revision(UUID templateId, long revision, String json, String digest,
            TemplateStatus status) {
        return new WorkerTemplateRevision(templateId, revision, json, digest, status, "alice", NOW, NOW, 0);
    }
}
