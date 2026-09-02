package io.agentteams.controlplane.organization;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.api.ApiErrorHandler;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ManagementProvisioningControllerTest {
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
                Set.of("external-user:manage")));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void initializesAnExternalUserThroughManagementApi() throws Exception {
        UUID integrationId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        var request = new OrganizationManagementController.ProvisionedUserRequest(
                "acme-corp", "ding-user-001", "Alice");
        var response = new OrganizationManagementController.ProvisionedUserResponse(
                integrationId, "acme-corp", "ding-user-001", "Alice", "ACTIVE", UUID.randomUUID());
        when(service.initializeProvisionedUser(integrationId, request, "idem-user-1", "trusted-admin"))
                .thenReturn(response);

        mvc.perform(post("/api/v1/management/integrations/{id}/provisioned-users", integrationId)
                        .header("Idempotency-Key", "idem-user-1")
                        .contentType("application/json")
                        .content("{\"externalOrganizationId\":\"acme-corp\","
                                + "\"externalUserId\":\"ding-user-001\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalUserId").value("ding-user-001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(service).initializeProvisionedUser(integrationId, request, "idem-user-1", "trusted-admin");
    }

    @Test
    void updatesAndDisablesAnExternalUser() throws Exception {
        UUID integrationId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        var request = new OrganizationManagementController.ProvisionedUserUpdateRequest("Alice Updated");
        var updated = new OrganizationManagementController.ProvisionedUserResponse(
                integrationId, "acme-corp", "ding-user-001", "Alice Updated", "ACTIVE", UUID.randomUUID());
        when(service.updateProvisionedUser(integrationId, "acme-corp", "ding-user-001", request, "idem-user-2", "trusted-admin"))
            .thenReturn(updated);
        when(service.disableProvisionedUser(integrationId, "acme-corp", "ding-user-001", "idem-user-3", "trusted-admin"))
                .thenReturn(new OrganizationManagementController.ProvisionedUserResponse(
                        integrationId, "acme-corp", "ding-user-001", "Alice Updated", "DISABLED", updated.internalUserId()));

        mvc.perform(put("/api/v1/management/integrations/{id}/provisioned-users/{externalUserId}",
                        integrationId, "ding-user-001")
                        .header("Idempotency-Key", "idem-user-2")
                        .param("externalOrganizationId", "acme-corp")
                        .contentType("application/json")
                        .content("{\"displayName\":\"Alice Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice Updated"));
        mvc.perform(post("/api/v1/management/integrations/{id}/provisioned-users/{externalUserId}/disable",
                        integrationId, "ding-user-001")
                        .header("Idempotency-Key", "idem-user-3")
                        .param("externalOrganizationId", "acme-corp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        verify(service).updateProvisionedUser(integrationId, "acme-corp", "ding-user-001", request, "idem-user-2", "trusted-admin");
        verify(service).disableProvisionedUser(integrationId, "acme-corp", "ding-user-001", "idem-user-3", "trusted-admin");
    }

    @Test
    void listsEffectiveMembershipsForAnExternalUser() throws Exception {
        UUID integrationId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(service.listProvisionedUserMemberships(integrationId, "acme-corp", "ding-user-001", "trusted-admin"))
                .thenReturn(List.of(new OrganizationManagementController.ProvisionedMembershipResponse(
                        "organization", "acme-org", "Acme", "ADMIN")));

        mvc.perform(get("/api/v1/management/integrations/{id}/provisioned-users/{externalUserId}/memberships",
                        integrationId, "ding-user-001")
                        .param("externalOrganizationId", "acme-corp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scopeType").value("organization"))
                .andExpect(jsonPath("$[0].role").value("ADMIN"));

        verify(service).listProvisionedUserMemberships(integrationId, "acme-corp", "ding-user-001", "trusted-admin");
    }
}
