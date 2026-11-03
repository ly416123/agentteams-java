package io.agentteams.controlplane.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.service.IdempotencyService;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationManagementScopeTest {
    @Test
    void organizationAdminSeesOnlyAssignedOrganizationsInOrganizationList() {
        UUID organizationA = UUID.randomUUID();
        OrganizationManagementRepository repository = mock(OrganizationManagementRepository.class);
        ManagementAuthorizationService authorization = mock(ManagementAuthorizationService.class);
        when(authorization.isPlatformAdmin()).thenReturn(false);
        when(authorization.currentOrganizationIds()).thenReturn(List.of(organizationA));
        when(authorization.effectivePermissions()).thenReturn(new ManagementAuthorizationService.EffectivePermissions(
                List.of(), List.of("organization:read")));
        when(repository.listOrganizations(List.of(organizationA))).thenReturn(List.of(
                new OrganizationManagementController.OrganizationResponse(organizationA, "Org A", "ACTIVE", 1)));
        var service = new OrganizationManagementService(repository, Clock.systemUTC(), new IdempotencyService(),
                (AuditRecorder) event -> { }, authorization);

        assertThat(service.listOrganizations())
                .extracting(OrganizationManagementController.OrganizationResponse::id)
                .containsExactly(organizationA);
        verify(authorization).currentOrganizationIds();
        verify(authorization, never()).requirePlatformAdmin();
        verify(repository, never()).listOrganizations();
    }

    @Test
    void subjectWithoutOrganizationAssignmentsDoesNotQueryOrganizations() {
        OrganizationManagementRepository repository = mock(OrganizationManagementRepository.class);
        ManagementAuthorizationService authorization = mock(ManagementAuthorizationService.class);
        when(authorization.isPlatformAdmin()).thenReturn(false);
        when(authorization.currentOrganizationIds()).thenReturn(List.of());
        var service = new OrganizationManagementService(repository, Clock.systemUTC(), new IdempotencyService(),
                (AuditRecorder) event -> { }, authorization);

        assertThat(service.listOrganizations()).isEmpty();

        verify(repository, org.mockito.Mockito.never()).listOrganizations();
        verify(repository, org.mockito.Mockito.never()).listOrganizations(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void managementRoleMustIncludeTheSpecificActionBeforeCredentialDataIsAccessed() {
        UUID organizationId = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();
        OrganizationManagementRepository repository = mock(OrganizationManagementRepository.class);
        ManagementAuthorizationService authorization = mock(ManagementAuthorizationService.class);
        when(repository.findIntegration(integrationId)).thenReturn(Optional.of(
                new OrganizationManagementController.IntegrationResponse(integrationId, organizationId,
                        "integration", "ACTIVE", 1)));
        when(authorization.effectivePermissions()).thenReturn(
                new ManagementAuthorizationService.EffectivePermissions(List.of(), List.of("integration:read")));
        var service = new OrganizationManagementService(repository, Clock.systemUTC(), new IdempotencyService(),
                (AuditRecorder) event -> { }, authorization);

        assertThatThrownBy(() -> service.listCredentials(integrationId))
                .isInstanceOf(io.agentteams.controlplane.security.AuthorizationException.class);
        assertThatThrownBy(() -> service.updateProvisioningPolicy(integrationId,
                new OrganizationManagementController.ProvisioningPolicyRequest(false, false, 0), "actor"))
                .isInstanceOf(io.agentteams.controlplane.security.AuthorizationException.class);

        verify(repository, never()).listCredentials(integrationId);
        verify(repository, never()).updateProvisioningPolicy(org.mockito.ArgumentMatchers.eq(integrationId),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }
}
