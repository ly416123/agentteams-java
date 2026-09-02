package io.agentteams.controlplane.organization;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

class OrganizationManagementControllerTest {
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
                Set.of("platform:organization:create", "organization:admin", "user:manage", "external-user:manage")));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void createsOrganizationAndTenantWithManagementPermissions() throws Exception {
        UUID organizationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(service.createOrganization("Acme", "idem-org-1", "trusted-admin")).thenReturn(
                new OrganizationManagementController.OrganizationResponse(organizationId, "Acme", "ACTIVE", 0));

        mvc.perform(post("/api/v1/management/organizations")
                        .contentType("application/json")
                        .header("Idempotency-Key", "idem-org-1")
                        .content("{\"name\":\"Acme\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(organizationId.toString()))
                .andExpect(jsonPath("$.name").value("Acme"));

        verify(service).createOrganization("Acme", "idem-org-1", "trusted-admin");
    }

    @Test
    void createsTenantUnderOrganization() throws Exception {
        UUID organizationId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID tenantId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(service.createTenant(organizationId, "Core", "idem-tenant-1", "trusted-admin")).thenReturn(
                new OrganizationManagementController.TenantResponse(tenantId, organizationId, "Core", "ACTIVE", 0));

        mvc.perform(post("/api/v1/management/organizations/{id}/tenants", organizationId)
                        .contentType("application/json")
                        .header("Idempotency-Key", "idem-tenant-1")
                        .content("{\"name\":\"Core\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(tenantId.toString()))
                .andExpect(jsonPath("$.organizationId").value(organizationId.toString()));

        verify(service).createTenant(organizationId, "Core", "idem-tenant-1", "trusted-admin");
    }

    @Test
    void updatesOrganizationAndTenantStatusWithExpectedVersions() throws Exception {
        UUID organizationId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID tenantId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(service.updateOrganizationStatus(organizationId, 3L, "SUSPENDED", "trusted-admin")).thenReturn(
                new OrganizationManagementController.OrganizationResponse(organizationId, "Acme", "SUSPENDED", 4));
        when(service.updateTenantStatus(tenantId, 5L, "SUSPENDED", "trusted-admin")).thenReturn(
                new OrganizationManagementController.TenantResponse(tenantId, organizationId, "Core", "SUSPENDED", 6));

        mvc.perform(post("/api/v1/management/organizations/{id}/status", organizationId)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":3,\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.version").value(4));
        mvc.perform(post("/api/v1/management/tenants/{id}/status", tenantId)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":5,\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.version").value(6));

        verify(service).updateOrganizationStatus(organizationId, 3L, "SUSPENDED", "trusted-admin");
        verify(service).updateTenantStatus(tenantId, 5L, "SUSPENDED", "trusted-admin");
    }

    @Test
    void createsInternalUserThroughManagementApi() throws Exception {
        UUID userId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        when(service.createUser("alice", "Alice", "trusted-admin")).thenReturn(
                new OrganizationManagementController.UserResponse(userId, "alice", "Alice", "ACTIVE", 0));

        mvc.perform(post("/api/v1/management/users")
                        .contentType("application/json")
                        .content("{\"subject\":\"alice\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(service).createUser("alice", "Alice", "trusted-admin");
    }

    @Test
    void managesOrganizationMembershipAndExternalIdentityMapping() throws Exception {
        UUID organizationId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID integrationId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID userId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID identityId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        when(service.upsertOrganizationMembership(organizationId, "alice", "ADMIN", "trusted-admin")).thenReturn(
                new OrganizationManagementController.MembershipResponse(organizationId, "alice", "ADMIN"));
        var request = new OrganizationManagementController.ExternalIdentityRequest(organizationId, userId,
                "acme-corp", "ding-user-001");
        when(service.upsertExternalIdentity(integrationId, request, "trusted-admin")).thenReturn(
                new OrganizationManagementController.ExternalIdentityResponse(identityId, integrationId, organizationId,
                        userId, "acme-corp", "ding-user-001", "ACTIVE"));

        mvc.perform(post("/api/v1/management/organizations/{id}/memberships", organizationId)
                        .contentType("application/json")
                        .content("{\"subject\":\"alice\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(post("/api/v1/management/integrations/{id}/external-identities", integrationId)
                        .contentType("application/json")
                        .content("{\"organizationId\":\"22222222-2222-2222-2222-222222222222\","
                                + "\"internalUserId\":\"66666666-6666-6666-6666-666666666666\","
                                + "\"externalOrganizationId\":\"acme-corp\",\"externalUserId\":\"ding-user-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalUserId").value("ding-user-001"));

        verify(service).upsertOrganizationMembership(organizationId, "alice", "ADMIN", "trusted-admin");
        verify(service).upsertExternalIdentity(integrationId, request, "trusted-admin");
    }
}
