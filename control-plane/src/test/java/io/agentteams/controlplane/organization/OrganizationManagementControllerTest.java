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
                Set.of("platform:organization:create", "organization:admin")));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void createsOrganizationAndTenantWithManagementPermissions() throws Exception {
        UUID organizationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(service.createOrganization("Acme", "trusted-admin")).thenReturn(
                new OrganizationManagementController.OrganizationResponse(organizationId, "Acme", "ACTIVE", 0));

        mvc.perform(post("/api/v1/management/organizations")
                        .contentType("application/json")
                        .content("{\"name\":\"Acme\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(organizationId.toString()))
                .andExpect(jsonPath("$.name").value("Acme"));

        verify(service).createOrganization("Acme", "trusted-admin");
    }

    @Test
    void createsTenantUnderOrganization() throws Exception {
        UUID organizationId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID tenantId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(service.createTenant(organizationId, "Core", "trusted-admin")).thenReturn(
                new OrganizationManagementController.TenantResponse(tenantId, organizationId, "Core", "ACTIVE", 0));

        mvc.perform(post("/api/v1/management/organizations/{id}/tenants", organizationId)
                        .contentType("application/json")
                        .content("{\"name\":\"Core\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(tenantId.toString()))
                .andExpect(jsonPath("$.organizationId").value(organizationId.toString()));

        verify(service).createTenant(organizationId, "Core", "trusted-admin");
    }
}
