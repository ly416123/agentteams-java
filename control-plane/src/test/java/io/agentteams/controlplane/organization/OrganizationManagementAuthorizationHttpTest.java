package io.agentteams.controlplane.organization;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.api.ApiErrorHandler;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.security.ApiAuthenticationFilter;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.IdentityTokenValidator;
import io.agentteams.controlplane.security.SignatureAlgorithm;
import io.agentteams.controlplane.service.IdempotencyService;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OrganizationManagementAuthorizationHttpTest {
    private static final String TOKEN = "valid-token";

    @Test
    void authenticatedOrganizationAdminCannotMutateAnotherOrganization() throws Exception {
        UUID organizationA = UUID.randomUUID();
        UUID organizationB = UUID.randomUUID();
        ManagementRoleRepository roles = mock(ManagementRoleRepository.class);
        OrganizationManagementRepository repository = mock(OrganizationManagementRepository.class);
        when(roles.hasRole("org-a-admin", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(false);
        when(roles.hasRole("org-a-admin", "ORGANIZATION", organizationA, "ORGANIZATION_ADMIN")).thenReturn(true);
        when(roles.hasRole("org-a-admin", "ORGANIZATION", organizationB, "ORGANIZATION_ADMIN")).thenReturn(false);
        ManagementAuthorizationService authorization = new ManagementAuthorizationService(roles);
        OrganizationManagementService service = new OrganizationManagementService(repository, Clock.systemUTC(),
                new IdempotencyService(), (AuditRecorder) event -> { }, authorization);
        IdentityTokenValidator validator = token -> TOKEN.equals(token)
                ? Optional.of(new IdentityTokenValidator.IdentityPrincipal("org-a-admin",
                        new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                        Set.of("platform:organization:create", "user:manage", "management:role:manage",
                                "credential:rotate", "credential:revoke")))
                : Optional.empty();
        var filter = new ApiAuthenticationFilter(validator);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrganizationManagementController(service))
                .addFilters(filter)
                .setControllerAdvice(new ApiErrorHandler())
                .build();

        mvc.perform(post("/api/v1/management/organizations/{id}/tenants", organizationB)
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Idempotency-Key", "tenant-create-1")
                        .contentType("application/json")
                        .content("{\"name\":\"Unauthorized tenant\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("permission denied"));

        verify(repository, never()).findOrganization(organizationB);
        verify(repository, never()).insertTenantIdempotent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(organizationB), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void authenticatedMemberCannotCreateInternalUsersOrGrantManagementRoles() throws Exception {
        ManagementRoleRepository roles = mock(ManagementRoleRepository.class);
        OrganizationManagementRepository repository = mock(OrganizationManagementRepository.class);
        MockMvc mvc = mvc(roles, repository);

        mvc.perform(post("/api/v1/management/users")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json")
                        .content("{\"subject\":\"new-user\",\"displayName\":\"New User\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("permission denied"));
        mvc.perform(post("/api/v1/management/authorizations/platform/admins")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json")
                        .content("{\"subject\":\"attacker\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(repository, never()).insertUser(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(roles, never()).upsert(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void organizationAdminCannotRotateCredentialsOwnedByAnotherOrganization() throws Exception {
        UUID organizationA = UUID.randomUUID();
        UUID organizationB = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        ManagementRoleRepository roles = mock(ManagementRoleRepository.class);
        OrganizationManagementRepository repository = mock(OrganizationManagementRepository.class);
        when(roles.hasRole("org-a-admin", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(false);
        when(roles.hasRole("org-a-admin", "ORGANIZATION", organizationA, "ORGANIZATION_ADMIN")).thenReturn(true);
        when(roles.hasRole("org-a-admin", "ORGANIZATION", organizationB, "ORGANIZATION_ADMIN")).thenReturn(false);
        when(repository.findCredential(credentialId)).thenReturn(Optional.of(
                new OrganizationManagementRepository.CredentialRecord(credentialId, integrationId, "primary",
                        "AKIA-1", "secret://existing", SignatureAlgorithm.HMAC_SHA256, "ACTIVE", null, 2)));
        when(repository.findIntegration(integrationId)).thenReturn(Optional.of(
                new OrganizationManagementController.IntegrationResponse(integrationId, organizationB,
                        "foreign integration", "ACTIVE", 1)));
        MockMvc mvc = mvc(roles, repository);

        mvc.perform(post("/api/v1/management/credentials/{id}/rotate", credentialId)
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Idempotency-Key", "rotate-foreign")
                        .contentType("application/json")
                        .content("{\"expectedVersion\":2,\"credentialRef\":\"secret://new\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("permission denied"));

        verify(repository, never()).updateCredentialIdempotent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(java.time.Instant.class), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void organizationAdminCannotChangeAnotherOrganizationsStatusOrMemberships() throws Exception {
        UUID organizationA = UUID.randomUUID();
        UUID organizationB = UUID.randomUUID();
        ManagementRoleRepository roles = mock(ManagementRoleRepository.class);
        OrganizationManagementRepository repository = mock(OrganizationManagementRepository.class);
        when(roles.hasRole("org-a-admin", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(false);
        when(roles.hasRole("org-a-admin", "ORGANIZATION", organizationA, "ORGANIZATION_ADMIN")).thenReturn(true);
        when(roles.hasRole("org-a-admin", "ORGANIZATION", organizationB, "ORGANIZATION_ADMIN")).thenReturn(false);
        MockMvc mvc = mvc(roles, repository);

        mvc.perform(post("/api/v1/management/organizations/{id}/status", organizationB)
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1,\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("permission denied"));
        mvc.perform(post("/api/v1/management/organizations/{id}/memberships", organizationB)
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json")
                        .content("{\"subject\":\"victim\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("permission denied"));

        verify(repository, never()).updateOrganizationStatus(org.mockito.ArgumentMatchers.eq(organizationB),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).upsertOrganizationMembership(org.mockito.ArgumentMatchers.eq(organizationB),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void organizationAdminCannotReadAnotherOrganizationsIntegrationData() throws Exception {
        UUID organizationA = UUID.randomUUID();
        UUID organizationB = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();
        ManagementRoleRepository roles = mock(ManagementRoleRepository.class);
        OrganizationManagementRepository repository = mock(OrganizationManagementRepository.class);
        when(roles.hasRole("org-a-admin", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(false);
        when(roles.hasRole("org-a-admin", "ORGANIZATION", organizationA, "ORGANIZATION_ADMIN")).thenReturn(true);
        when(roles.hasRole("org-a-admin", "ORGANIZATION", organizationB, "ORGANIZATION_ADMIN")).thenReturn(false);
        when(repository.findIntegration(integrationId)).thenReturn(Optional.of(
                new OrganizationManagementController.IntegrationResponse(integrationId, organizationB,
                        "foreign integration", "ACTIVE", 1)));
        MockMvc mvc = mvc(roles, repository);

        mvc.perform(get("/api/v1/management/integrations/{id}/credentials", integrationId)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("permission denied"));
        mvc.perform(get("/api/v1/management/integrations/{id}/external-identities", integrationId)
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("permission denied"));
        mvc.perform(get("/api/v1/management/integrations/{id}/provisioned-users/external-1/memberships", integrationId)
                        .param("externalOrganizationId", "foreign-org")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("permission denied"));

        verify(repository, never()).listCredentials(integrationId);
        verify(repository, never()).listExternalIdentities(integrationId);
        verify(repository, never()).listProvisionedUserMemberships(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void organizationAdminCannotReadOrChangeAnyForeignOrganizationLifecycleResource() throws Exception {
        UUID organizationB = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ManagementRoleRepository roles = mock(ManagementRoleRepository.class);
        OrganizationManagementRepository repository = mock(OrganizationManagementRepository.class);
        when(repository.organizationIdForTenant(tenantB)).thenReturn(Optional.of(organizationB));
        when(repository.findIntegration(integrationId)).thenReturn(Optional.of(
                new OrganizationManagementController.IntegrationResponse(integrationId, organizationB,
                        "foreign integration", "ACTIVE", 1)));
        when(repository.findCredential(credentialId)).thenReturn(Optional.of(
                new OrganizationManagementRepository.CredentialRecord(credentialId, integrationId, "primary",
                        "AKIA-1", "secret://existing", SignatureAlgorithm.HMAC_SHA256, "ACTIVE", null, 2)));
        MockMvc mvc = mvc(roles, repository);
        String organization = organizationB.toString();
        String tenant = tenantB.toString();
        String integration = integrationId.toString();
        String credential = credentialId.toString();

        var deniedRoutes = java.util.List.of(
                new DeniedRoute("GET", "/api/v1/management/organizations/" + organization + "/tenants", null),
                new DeniedRoute("GET", "/api/v1/management/organizations/" + organization + "/integrations", null),
                new DeniedRoute("GET", "/api/v1/management/organizations/" + organization + "/memberships", null),
                new DeniedRoute("POST", "/api/v1/management/organizations/" + organization + "/tenants",
                        "{\"name\":\"foreign tenant\"}"),
                new DeniedRoute("POST", "/api/v1/management/organizations/" + organization + "/integrations",
                        "{\"name\":\"foreign integration\"}"),
                new DeniedRoute("POST", "/api/v1/management/organizations/" + organization + "/status",
                        "{\"expectedVersion\":1,\"status\":\"SUSPENDED\"}"),
                new DeniedRoute("POST", "/api/v1/management/organizations/" + organization + "/memberships",
                        "{\"subject\":\"target\",\"role\":\"ADMIN\"}"),
                new DeniedRoute("POST", "/api/v1/management/tenants/" + tenant + "/status",
                        "{\"expectedVersion\":1,\"status\":\"SUSPENDED\"}"),
                new DeniedRoute("GET", "/api/v1/management/integrations/" + integration + "/credentials", null),
                new DeniedRoute("GET", "/api/v1/management/integrations/" + integration + "/external-identities", null),
                new DeniedRoute("POST", "/api/v1/management/integrations/" + integration + "/credentials",
                        "{\"label\":\"secondary\",\"credentialRef\":\"secret://new\"}"),
                new DeniedRoute("PUT", "/api/v1/management/integrations/" + integration
                        + "/provisioning-policy", "{\"allowAutoCreate\":false,\"allowPlatformAdmin\":false,\"expectedVersion\":1}"),
                new DeniedRoute("POST", "/api/v1/management/integrations/" + integration + "/external-identities",
                        "{\"organizationId\":\"" + organization + "\",\"internalUserId\":\"" + targetId
                                + "\",\"externalOrganizationId\":\"foreign\",\"externalUserId\":\"user\"}"),
                new DeniedRoute("POST", "/api/v1/management/integrations/" + integration + "/provisioned-users",
                        "{\"externalOrganizationId\":\"foreign\",\"externalUserId\":\"user\",\"displayName\":\"User\"}"),
                new DeniedRoute("PUT", "/api/v1/management/integrations/" + integration
                        + "/provisioned-users/user?externalOrganizationId=foreign", "{\"displayName\":\"Updated\"}"),
                new DeniedRoute("POST", "/api/v1/management/integrations/" + integration
                        + "/provisioned-users/user/disable?externalOrganizationId=foreign", null),
                new DeniedRoute("GET", "/api/v1/management/integrations/" + integration
                        + "/provisioned-users/user/memberships?externalOrganizationId=foreign", null),
                new DeniedRoute("POST", "/api/v1/management/credentials/" + credential + "/rotate",
                        "{\"expectedVersion\":2,\"credentialRef\":\"secret://rotated\"}"),
                new DeniedRoute("POST", "/api/v1/management/credentials/" + credential + "/revoke",
                        "{\"expectedVersion\":2}"),
                new DeniedRoute("GET", "/api/v1/management/users", null));

        for (DeniedRoute route : deniedRoutes) {
            String path = route.path().split("\\?", 2)[0];
            var builder = request(HttpMethod.valueOf(route.method()), path)
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Idempotency-Key", "foreign-scope-key");
            if (route.path().contains("externalOrganizationId=")) {
                builder.param("externalOrganizationId", "foreign");
            }
            if (route.body() != null) builder.contentType("application/json").content(route.body());
            mvc.perform(builder)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value("permission denied"));
        }

        verify(repository, never()).listTenants(organizationB);
        verify(repository, never()).listIntegrations(organizationB);
        verify(repository, never()).listOrganizationMemberships(organizationB);
        verify(repository, never()).listCredentials(integrationId);
        verify(repository, never()).listExternalIdentities(integrationId);
        verify(repository, never()).listProvisionedUserMemberships(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(repository, never()).insertTenantIdempotent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(organizationB), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).insertIntegration(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(organizationB), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).updateOrganizationStatus(org.mockito.ArgumentMatchers.eq(organizationB),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).upsertOrganizationMembership(org.mockito.ArgumentMatchers.eq(organizationB),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).insertCredentialIdempotent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(integrationId), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).updateProvisioningPolicy(org.mockito.ArgumentMatchers.eq(integrationId),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).upsertExternalIdentity(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(integrationId), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).provisionExternalUser(org.mockito.ArgumentMatchers.eq(integrationId),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).updateProvisionedUser(org.mockito.ArgumentMatchers.eq(integrationId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).disableProvisionedUser(org.mockito.ArgumentMatchers.eq(integrationId),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).updateCredentialIdempotent(org.mockito.ArgumentMatchers.eq(credentialId),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void organizationAdminCannotRevokeManagementRoles() throws Exception {
        UUID organizationA = UUID.randomUUID();
        ManagementRoleRepository roles = mock(ManagementRoleRepository.class);
        OrganizationManagementRepository repository = mock(OrganizationManagementRepository.class);
        when(roles.hasRole("org-a-admin", "PLATFORM", null, "PLATFORM_ADMIN")).thenReturn(false);
        when(roles.hasRole("org-a-admin", "ORGANIZATION", organizationA, "ORGANIZATION_ADMIN")).thenReturn(true);
        MockMvc mvc = mvc(roles, repository);

        mvc.perform(post("/api/v1/management/authorizations/organizations/{id}/admins/victim/revoke", organizationA)
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType("application/json")
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(roles, never()).updateStatus(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void tokenClaimsWithoutDatabaseManagementRolesCannotUseSensitiveManagementRoutes() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        ManagementRoleRepository roles = mock(ManagementRoleRepository.class);
        OrganizationManagementRepository repository = mock(OrganizationManagementRepository.class);
        MockMvc mvc = mvc(roles, repository);
        String organization = organizationId.toString();
        String integration = integrationId.toString();
        String resource = resourceId.toString();

        var deniedRoutes = java.util.List.of(
                new DeniedRoute("POST", "/api/v1/management/organizations", "{\"name\":\"Acme\"}"),
                new DeniedRoute("POST", "/api/v1/management/organizations/" + organization + "/tenants",
                        "{\"name\":\"tenant\"}"),
                new DeniedRoute("POST", "/api/v1/management/organizations/" + organization + "/status",
                        "{\"expectedVersion\":1,\"status\":\"SUSPENDED\"}"),
                new DeniedRoute("POST", "/api/v1/management/tenants/" + resource + "/status",
                        "{\"expectedVersion\":1,\"status\":\"SUSPENDED\"}"),
                new DeniedRoute("POST", "/api/v1/management/organizations/" + organization + "/integrations",
                        "{\"name\":\"integration\"}"),
                new DeniedRoute("POST", "/api/v1/management/integrations/" + integration + "/credentials",
                        "{\"label\":\"primary\",\"credentialRef\":\"secret://ref\"}"),
                new DeniedRoute("POST", "/api/v1/management/credentials/" + resource + "/rotate",
                        "{\"expectedVersion\":1,\"credentialRef\":\"secret://new\"}"),
                new DeniedRoute("POST", "/api/v1/management/credentials/" + resource + "/revoke",
                        "{\"expectedVersion\":1}"),
                new DeniedRoute("PUT", "/api/v1/management/integrations/" + integration + "/provisioning-policy",
                        "{\"allowAutoCreate\":false,\"allowPlatformAdmin\":false,\"expectedVersion\":1}"),
                new DeniedRoute("POST", "/api/v1/management/users",
                        "{\"subject\":\"target\",\"displayName\":\"Target\"}"),
                new DeniedRoute("POST", "/api/v1/management/users/" + resource + "/status",
                        "{\"expectedVersion\":1,\"status\":\"SUSPENDED\"}"),
                new DeniedRoute("POST", "/api/v1/management/organizations/" + organization + "/memberships",
                        "{\"subject\":\"target\",\"role\":\"ADMIN\"}"),
                new DeniedRoute("POST", "/api/v1/management/integrations/" + integration + "/external-identities",
                        "{\"organizationId\":\"" + organization + "\",\"internalUserId\":\"" + resource
                                + "\",\"externalOrganizationId\":\"ext-org\",\"externalUserId\":\"ext-user\"}"),
                new DeniedRoute("POST", "/api/v1/management/integrations/" + integration + "/provisioned-users",
                        "{\"externalOrganizationId\":\"ext-org\",\"externalUserId\":\"ext-user\",\"displayName\":\"Target\"}"),
                new DeniedRoute("PUT", "/api/v1/management/integrations/" + integration
                        + "/provisioned-users/ext-user?externalOrganizationId=ext-org", "{\"displayName\":\"Updated\"}"),
                new DeniedRoute("POST", "/api/v1/management/integrations/" + integration
                        + "/provisioned-users/ext-user/disable?externalOrganizationId=ext-org", null),
                new DeniedRoute("POST", "/api/v1/management/authorizations/platform/admins",
                        "{\"subject\":\"attacker\"}"),
                new DeniedRoute("POST", "/api/v1/management/authorizations/organizations/" + organization + "/admins",
                        "{\"subject\":\"attacker\"}"),
                new DeniedRoute("POST", "/api/v1/management/authorizations/platform/admins/target/revoke",
                        "{\"expectedVersion\":1}"),
                new DeniedRoute("POST", "/api/v1/management/authorizations/organizations/" + organization
                        + "/admins/target/revoke", "{\"expectedVersion\":1}"));

        for (DeniedRoute route : deniedRoutes) {
            String path = route.path().split("\\?", 2)[0];
            var builder = request(HttpMethod.valueOf(route.method()), path)
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Idempotency-Key", "denied-route-key");
            if (route.path().contains("externalOrganizationId=")) {
                builder.param("externalOrganizationId", "ext-org");
            }
            if (route.body() != null) {
                builder.contentType("application/json").content(route.body());
            }
            mvc.perform(builder)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value("permission denied"));
        }

        var deniedReads = java.util.List.of(
                "/api/v1/management/organizations/" + organization + "/tenants",
                "/api/v1/management/organizations/" + organization + "/integrations",
                "/api/v1/management/organizations/" + organization + "/memberships",
                "/api/v1/management/integrations/" + integration + "/credentials",
                "/api/v1/management/integrations/" + integration + "/external-identities",
                "/api/v1/management/integrations/" + integration
                        + "/provisioned-users/external-1/memberships?externalOrganizationId=external-org",
                "/api/v1/management/users",
                "/api/v1/management/authorizations/assignments?scopeType=PLATFORM");
        for (String route : deniedReads) {
            String path = route.split("\\?", 2)[0];
            var builder = get(path).header("Authorization", "Bearer " + TOKEN);
            if (route.contains("scopeType=")) builder.param("scopeType", "PLATFORM");
            if (route.contains("externalOrganizationId=")) builder.param("externalOrganizationId", "external-org");
            mvc.perform(builder)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").value("permission denied"));
        }

        verify(repository, never()).insertOrganizationIdempotent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(java.time.Instant.class));
        verify(repository, never()).insertTenantIdempotent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).insertIntegration(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).insertCredentialIdempotent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).updateCredentialIdempotent(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).insertUser(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).updateUserStatus(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).upsertOrganizationMembership(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).updateProvisioningPolicy(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).upsertExternalIdentity(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).provisionExternalUser(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).updateProvisionedUser(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).disableProvisionedUser(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(roles, never()).upsert(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(roles, never()).updateStatus(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    private record DeniedRoute(String method, String path, String body) { }

    private static MockMvc mvc(ManagementRoleRepository roles, OrganizationManagementRepository repository) {
        ManagementAuthorizationService authorization = new ManagementAuthorizationService(roles);
        OrganizationManagementService service = new OrganizationManagementService(repository, Clock.systemUTC(),
                new IdempotencyService(), (AuditRecorder) event -> { }, authorization);
        IdentityTokenValidator validator = token -> TOKEN.equals(token)
                ? Optional.of(new IdentityTokenValidator.IdentityPrincipal("org-a-admin",
                        new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                        Set.of("platform:organization:create", "user:manage", "management:role:manage",
                                "credential:rotate", "credential:revoke")))
                : Optional.empty();
        return MockMvcBuilders.standaloneSetup(new OrganizationManagementController(service),
                        new ManagementAuthorizationController(authorization))
                .addFilters(new ApiAuthenticationFilter(validator))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }
}
