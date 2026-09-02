package io.agentteams.controlplane.organization;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.controlplane.api.ApiErrorHandler;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class IntegrationManagementControllerTest {
    private MockMvc mvc;
    private OrganizationManagementService service;

    @BeforeEach
    void setUp() {
        service = mock(OrganizationManagementService.class);
        mvc = MockMvcBuilders.standaloneSetup(new OrganizationManagementController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
        PrincipalContext.set(new Principal("trusted-admin",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                Set.of("integration:manage", "credential:manage", "provisioning-policy:manage")));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void createsIntegrationCredentialAndHidesSecretAfterFirstResponse() throws Exception {
        UUID integrationId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID credentialId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(service.createIntegration(UUID.fromString("22222222-2222-2222-2222-222222222222"), "Docs",
                "trusted-admin")).thenReturn(
                new OrganizationManagementController.IntegrationResponse(integrationId,
                        UUID.fromString("22222222-2222-2222-2222-222222222222"), "Docs", "ACTIVE", 0));
        when(service.createCredential(integrationId, "svc", "k8s://prod/agentteams#sdk-secret", "credential-create-1",
                "trusted-admin")).thenReturn(
                new OrganizationManagementController.CredentialResponse(credentialId, integrationId, "svc",
                        "AKIA-1", 1, "ACTIVE"));

        mvc.perform(post("/api/v1/management/integrations/{id}/credentials", integrationId)
                        .header("Idempotency-Key", "credential-create-1")
                        .contentType("application/json")
                        .content("{\"label\":\"svc\",\"credentialRef\":\"k8s://prod/agentteams#sdk-secret\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").doesNotExist());

        verify(service).createCredential(integrationId, "svc", "k8s://prod/agentteams#sdk-secret", "credential-create-1",
                "trusted-admin");
    }

    @Test
    void rotatesRevokesAndUpdatesProvisioningPolicyWithVersionChecks() throws Exception {
        UUID integrationId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID credentialId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(service.rotateCredential(credentialId, 7L, "rot-1", "secret://rotated", "trusted-admin")).thenReturn(
                new OrganizationManagementController.CredentialResponse(credentialId, integrationId, "svc",
                        "AKIA-2", 8, "ACTIVE"));

        mvc.perform(post("/api/v1/management/credentials/{id}/rotate", credentialId)
                        .header("Idempotency-Key", "rot-1")
                        .contentType("application/json")
                        .content("{\"expectedVersion\":7,\"credentialRef\":\"secret://rotated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(8))
                .andExpect(jsonPath("$.secret").doesNotExist());

        when(service.revokeCredential(credentialId, 8L, "revoke-1", "trusted-admin")).thenReturn(
                new OrganizationManagementController.CredentialResponse(credentialId, integrationId, "svc",
                        "AKIA-2", 9, "REVOKED"));
        mvc.perform(post("/api/v1/management/credentials/{id}/revoke", credentialId)
                        .header("Idempotency-Key", "revoke-1")
                        .contentType("application/json")
                        .content("{\"expectedVersion\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.secret").doesNotExist());

        when(service.updateProvisioningPolicy(integrationId,
                new OrganizationManagementController.ProvisioningPolicyRequest(true, false, 3), "trusted-admin"))
                .thenReturn(new OrganizationManagementController.ProvisioningPolicyResponse(integrationId, true, false, 3));
        mvc.perform(put("/api/v1/management/integrations/{id}/provisioning-policy", integrationId)
                        .contentType("application/json")
                        .content("{\"allowAutoCreate\":true,\"allowPlatformAdmin\":false,\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowPlatformAdmin").value(false));

        verify(service).rotateCredential(credentialId, 7L, "rot-1", "secret://rotated", "trusted-admin");
        verify(service).revokeCredential(credentialId, 8L, "revoke-1", "trusted-admin");
        verify(service).updateProvisioningPolicy(integrationId,
                new OrganizationManagementController.ProvisioningPolicyRequest(true, false, 3), "trusted-admin");
    }

    @Test
    void credentialWritesReplayWithSameKeyAndRejectDifferentRequest() {
        PrincipalContext.set(new Principal("trusted-admin",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                Set.of("platform:organization:create", "integration:manage", "credential:manage")));
        OrganizationManagementService realService = new OrganizationManagementService();
        var organization = realService.createOrganization("Acme", "trusted-admin");
        var integration = realService.createIntegration(organization.id(), "Docs", "trusted-admin");

        var created = realService.createCredential(integration.id(), "svc", "secret://one", "create-1", "trusted-admin");
        var replayed = realService.createCredential(integration.id(), "svc", "secret://one", "create-1", "trusted-admin");

        assertThat(replayed).isEqualTo(created);
        assertThatThrownBy(() -> realService.createCredential(integration.id(), "svc", "secret://two", "create-1",
                "trusted-admin")).hasMessageContaining("idempotency key was reused");
    }

    @Test
    void credentialWritesRequireIdempotencyKey() throws Exception {
        UUID integrationId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        mvc.perform(post("/api/v1/management/integrations/{id}/credentials", integrationId)
                        .contentType("application/json")
                        .content("{\"label\":\"svc\",\"credentialRef\":\"secret://ref\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rotatesCredentialWithNewAccessKeyAndNeverSerializesSecret() throws Exception {
        PrincipalContext.set(new Principal("trusted-admin",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                Set.of("platform:organization:create", "integration:manage", "credential:manage")));
        OrganizationManagementService realService = new OrganizationManagementService();

        var organization = realService.createOrganization("Acme", "trusted-admin");
        var integration = realService.createIntegration(organization.id(), "Docs", "trusted-admin");
        var created = realService.createCredential(integration.id(), "svc", "trusted-admin");
        var rotated = realService.rotateCredential(created.id(), created.version(), "trusted-admin");

        assertThat(rotated.accessKeyId()).isNotEqualTo(created.accessKeyId());
        assertThat(new ObjectMapper().writeValueAsString(rotated)).doesNotContain("\"secret\"");
    }

    @Test
    void concurrentRotatesWithSameExpectedVersionAllowAtMostOneSuccess() throws Exception {
        Principal principal = new Principal("trusted-admin",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                Set.of("platform:organization:create", "integration:manage", "credential:manage"));
        OrganizationManagementService realService = new OrganizationManagementService();
        PrincipalContext.set(principal);
        var organization = realService.createOrganization("Acme", "trusted-admin");
        var integration = realService.createIntegration(organization.id(), "Docs", "trusted-admin");
        var created = realService.createCredential(integration.id(), "svc", "trusted-admin");
        PrincipalContext.clear();

        int workerCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var attempts = new java.util.ArrayList<Future<Boolean>>();
            for (int i = 0; i < workerCount; i++) {
                attempts.add(executor.submit(() -> {
                    PrincipalContext.set(principal);
                    try {
                        ready.countDown();
                        start.await();
                        realService.rotateCredential(created.id(), created.version(), "trusted-admin");
                        return true;
                    } catch (AuthorizationException expected) {
                        return false;
                    } finally {
                        PrincipalContext.clear();
                    }
                }));
            }
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long successes = 0;
            for (Future<Boolean> attempt : attempts) {
                if (attempt.get()) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void credentialStateToStringDoesNotContainSecret() throws Exception {
        PrincipalContext.set(new Principal("trusted-admin",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                Set.of("platform:organization:create", "integration:manage", "credential:manage")));
        OrganizationManagementService realService = new OrganizationManagementService();
        var organization = realService.createOrganization("Acme", "trusted-admin");
        var integration = realService.createIntegration(organization.id(), "Docs", "trusted-admin");
        realService.createCredential(integration.id(), "svc", "trusted-admin");

        Field credentialsField = OrganizationManagementService.class.getDeclaredField("credentials");
        credentialsField.setAccessible(true);
        Object state = ((java.util.Map<?, ?>) credentialsField.get(realService)).values().iterator().next();
        Field secretField = state.getClass().getDeclaredField("secret");
        secretField.setAccessible(true);
        String secret = (String) secretField.get(state);

        assertThat(state.toString()).doesNotContain(secret);
    }
}
