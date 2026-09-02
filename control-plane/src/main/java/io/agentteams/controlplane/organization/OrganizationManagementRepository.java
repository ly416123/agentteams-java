package io.agentteams.controlplane.organization;

import io.agentteams.controlplane.security.SignatureAlgorithm;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** Persistent metadata port for the management identity boundary. */
public interface OrganizationManagementRepository {
    OrganizationManagementController.OrganizationResponse insertOrganization(UUID id, String externalKey,
            String displayName, Instant now);

    OrganizationManagementController.OrganizationResponse insertOrganizationIdempotent(UUID id, String externalKey,
            String displayName, String idempotencyKey, String requestHash, Instant now);

    Optional<OrganizationManagementController.OrganizationResponse> findOrganization(UUID id);

    List<OrganizationManagementController.OrganizationResponse> listOrganizations();

    OrganizationManagementController.OrganizationResponse updateOrganizationStatus(UUID id, long expectedVersion,
            String status, Instant now);

    List<OrganizationManagementController.TenantResponse> listTenants(UUID organizationId);

    OrganizationManagementController.TenantResponse insertTenant(UUID id, UUID organizationId, String externalKey,
            String displayName, Instant now);

    OrganizationManagementController.TenantResponse insertTenantIdempotent(UUID id, UUID organizationId,
            String externalKey, String displayName, String idempotencyKey, String requestHash, Instant now);

    OrganizationManagementController.TenantResponse updateTenantStatus(UUID id, long expectedVersion, String status,
            Instant now);

    OrganizationManagementController.IntegrationResponse insertIntegration(UUID id, UUID organizationId,
            String externalKey, String displayName, Instant now);

    Optional<OrganizationManagementController.IntegrationResponse> findIntegration(UUID id);

    List<OrganizationManagementController.IntegrationResponse> listIntegrations(UUID organizationId);

    void insertDefaultProvisioningPolicy(UUID integrationId, Instant now);

    OrganizationManagementController.CredentialResponse insertCredential(UUID id, UUID integrationId, String label,
            String accessKeyId, String credentialRef, SignatureAlgorithm algorithm, Instant expiresAt, Instant now);

    OrganizationManagementController.CredentialResponse insertCredentialIdempotent(UUID id, UUID integrationId,
            String label, String accessKeyId, String credentialRef, SignatureAlgorithm algorithm, Instant expiresAt,
            String idempotencyKey, String requestHash, Instant now);

    Optional<CredentialRecord> findCredential(UUID id);

    List<OrganizationManagementController.CredentialResponse> listCredentials(UUID integrationId);

    Optional<CredentialRecord> updateCredential(UUID id, long expectedVersion, String accessKeyId,
            String credentialRef, String status, Instant expiresAt, Instant now);

    OrganizationManagementController.CredentialResponse updateCredentialIdempotent(UUID id, long expectedVersion,
            String accessKeyId, String credentialRef, String status, Instant expiresAt, String operation,
            String idempotencyKey, String requestHash, Instant now);

    OrganizationManagementController.ProvisioningPolicyResponse updateProvisioningPolicy(UUID integrationId,
            boolean allowAutoCreate, boolean allowPlatformAdmin, long expectedVersion, Instant now);

    Optional<OrganizationManagementController.ProvisioningPolicyResponse> findProvisioningPolicy(UUID integrationId);

    OrganizationManagementController.UserResponse insertUser(UUID id, String subject, String displayName, Instant now);

    List<OrganizationManagementController.UserResponse> listUsers();

    OrganizationManagementController.UserResponse updateUserStatus(UUID id, long expectedVersion, String status,
            Instant now);

    OrganizationManagementController.MembershipResponse upsertOrganizationMembership(UUID organizationId,
            String subject, String role, Instant now);

    List<OrganizationManagementController.MembershipResponse> listOrganizationMemberships(UUID organizationId);

    OrganizationManagementController.ExternalIdentityResponse upsertExternalIdentity(UUID id, UUID integrationId,
            UUID organizationId, UUID internalUserId, String externalOrganizationId, String externalUserId,
            Instant now);

    List<OrganizationManagementController.ExternalIdentityResponse> listExternalIdentities(UUID integrationId);

    OrganizationManagementController.ProvisionedUserResponse provisionExternalUser(UUID integrationId,
            UUID organizationId, String externalOrganizationId, String externalUserId, String displayName,
            String idempotencyKey, String requestHash, Instant now);

    OrganizationManagementController.ProvisionedUserResponse updateProvisionedUser(UUID integrationId,
            String externalOrganizationId, String externalUserId, String displayName, String idempotencyKey,
            String requestHash, Instant now);

    OrganizationManagementController.ProvisionedUserResponse disableProvisionedUser(UUID integrationId,
            String externalOrganizationId, String externalUserId, String idempotencyKey, String requestHash,
            Instant now);

    List<OrganizationManagementController.ProvisionedMembershipResponse> listProvisionedUserMemberships(
            UUID integrationId, UUID organizationId, String externalOrganizationId, String externalUserId);

    record CredentialRecord(UUID id, UUID integrationId, String label, String accessKeyId, String credentialRef,
            SignatureAlgorithm algorithm, String status, Instant expiresAt, long version) { }
}
