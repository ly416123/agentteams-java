package io.agentteams.controlplane.organization;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.api.ApiErrorHandler;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.util.Set;
import java.util.UUID;
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
        when(service.createCredential(integrationId, "svc", "trusted-admin")).thenReturn(
                new OrganizationManagementController.CredentialResponse(credentialId, integrationId, "svc",
                        "AKIA-1", "secret-1", 1, "ACTIVE"));

        mvc.perform(post("/api/v1/management/integrations/{id}/credentials", integrationId)
                        .contentType("application/json")
                        .content("{\"label\":\"svc\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").value("secret-1"));

        verify(service).createCredential(integrationId, "svc", "trusted-admin");
    }

    @Test
    void rotatesRevokesAndUpdatesProvisioningPolicyWithVersionChecks() throws Exception {
        UUID integrationId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID credentialId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(service.rotateCredential(credentialId, 7L, "trusted-admin")).thenReturn(
                new OrganizationManagementController.CredentialResponse(credentialId, integrationId, "svc",
                        "AKIA-2", null, 8, "ACTIVE"));

        mvc.perform(post("/api/v1/management/credentials/{id}/rotate", credentialId)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(8));

        when(service.revokeCredential(credentialId, 8L, "trusted-admin")).thenReturn(
                new OrganizationManagementController.CredentialResponse(credentialId, integrationId, "svc",
                        "AKIA-2", null, 9, "REVOKED"));
        mvc.perform(post("/api/v1/management/credentials/{id}/revoke", credentialId)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        when(service.updateProvisioningPolicy(integrationId, "trusted-admin",
                new OrganizationManagementController.ProvisioningPolicyRequest(true, false, 3)))
                .thenReturn(new OrganizationManagementController.ProvisioningPolicyResponse(integrationId, true, false, 3));
        mvc.perform(put("/api/v1/management/integrations/{id}/provisioning-policy", integrationId)
                        .contentType("application/json")
                        .content("{\"allowAutoCreate\":true,\"allowPlatformAdmin\":false,\"expectedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowPlatformAdmin").value(false));

        verify(service).rotateCredential(credentialId, 7L, "trusted-admin");
        verify(service).revokeCredential(credentialId, 8L, "trusted-admin");
        verify(service).updateProvisioningPolicy(integrationId, "trusted-admin",
                new OrganizationManagementController.ProvisioningPolicyRequest(true, false, 3));
    }
}
