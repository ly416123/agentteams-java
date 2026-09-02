package io.agentteams.controlplane.organization;

import io.agentteams.controlplane.security.PrincipalContext;
import java.util.UUID;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/management")
public final class OrganizationManagementController {
    private final OrganizationManagementService service;

    public OrganizationManagementController(OrganizationManagementService service) {
        this.service = service;
    }

    @GetMapping("/organizations")
    public List<OrganizationResponse> listOrganizations() { return service.listOrganizations(); }

    @GetMapping("/organizations/{id}/tenants")
    public List<TenantResponse> listTenants(@PathVariable UUID id) { return service.listTenants(id); }

    @GetMapping("/organizations/{id}/integrations")
    public List<IntegrationResponse> listIntegrations(@PathVariable UUID id) { return service.listIntegrations(id); }

    @GetMapping("/integrations/{id}/credentials")
    public List<CredentialResponse> listCredentials(@PathVariable UUID id) { return service.listCredentials(id); }

    @GetMapping("/integrations/{id}/external-identities")
    public List<ExternalIdentityResponse> listExternalIdentities(@PathVariable UUID id) {
        return service.listExternalIdentities(id);
    }

    @GetMapping("/organizations/{id}/memberships")
    public List<MembershipResponse> listMemberships(@PathVariable UUID id) {
        return service.listOrganizationMemberships(id);
    }

    @PostMapping("/organizations")
    public ResponseEntity<OrganizationResponse> createOrganization(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateOrganizationRequest request) {
        return ResponseEntity.status(201).body(service.createOrganization(request.name(), requiredKey(idempotencyKey),
                PrincipalContext.actorOr("api")));
    }

    @PostMapping("/organizations/{id}/tenants")
    public ResponseEntity<TenantResponse> createTenant(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateTenantRequest request) {
        return ResponseEntity.status(201).body(service.createTenant(id, request.name(), requiredKey(idempotencyKey),
                PrincipalContext.actorOr("api")));
    }

    @PostMapping("/organizations/{id}/status")
    public OrganizationResponse updateOrganizationStatus(@PathVariable UUID id,
            @RequestBody StatusVersionRequest request) {
        return service.updateOrganizationStatus(id, request.expectedVersion(), request.status(),
                PrincipalContext.actorOr("api"));
    }

    @PostMapping("/tenants/{id}/status")
    public TenantResponse updateTenantStatus(@PathVariable UUID id, @RequestBody StatusVersionRequest request) {
        return service.updateTenantStatus(id, request.expectedVersion(), request.status(),
                PrincipalContext.actorOr("api"));
    }

    @PostMapping("/organizations/{id}/integrations")
    public ResponseEntity<IntegrationResponse> createIntegration(@PathVariable UUID id,
            @RequestBody CreateIntegrationRequest request) {
        return ResponseEntity.status(201).body(service.createIntegration(id, request.name(), PrincipalContext.actorOr("api")));
    }

    @PostMapping("/integrations/{id}/credentials")
    public ResponseEntity<CredentialResponse> createCredential(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateCredentialRequest request) {
        return ResponseEntity.status(201).body(service.createCredential(id, request.label(), request.credentialRef(),
                requiredKey(idempotencyKey), PrincipalContext.actorOr("api")));
    }

    @PostMapping("/credentials/{id}/rotate")
    public CredentialResponse rotateCredential(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CredentialVersionRequest request) {
        String actor = PrincipalContext.actorOr("api");
        return service.rotateCredential(id, request.expectedVersion(), requiredKey(idempotencyKey), request.credentialRef(), actor);
    }

    @PostMapping("/credentials/{id}/revoke")
    public CredentialResponse revokeCredential(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CredentialVersionRequest request) {
        return service.revokeCredential(id, request.expectedVersion(), requiredKey(idempotencyKey),
                PrincipalContext.actorOr("api"));
    }

    @PutMapping("/integrations/{id}/provisioning-policy")
    public ProvisioningPolicyResponse updateProvisioningPolicy(@PathVariable UUID id,
            @RequestBody ProvisioningPolicyRequest request) {
        return service.updateProvisioningPolicy(id, request, PrincipalContext.actorOr("api"));
    }

    @PostMapping("/users")
    public ResponseEntity<UserResponse> createUser(@RequestBody CreateUserRequest request) {
        return ResponseEntity.status(201).body(service.createUser(request.subject(), request.displayName(),
                PrincipalContext.actorOr("api")));
    }

    @GetMapping("/users")
    public List<UserResponse> listUsers() { return service.listUsers(); }

    @PostMapping("/users/{id}/status")
    public UserResponse updateUserStatus(@PathVariable UUID id, @RequestBody UserStatusRequest request) {
        return service.updateUserStatus(id, request.expectedVersion(), request.status(), PrincipalContext.actorOr("api"));
    }

    @PostMapping("/organizations/{id}/memberships")
    public MembershipResponse upsertOrganizationMembership(@PathVariable UUID id,
            @RequestBody MembershipRequest request) {
        return service.upsertOrganizationMembership(id, request.subject(), request.role(), PrincipalContext.actorOr("api"));
    }

    @PostMapping("/integrations/{id}/external-identities")
    public ExternalIdentityResponse upsertExternalIdentity(@PathVariable UUID id,
            @RequestBody ExternalIdentityRequest request) {
        return service.upsertExternalIdentity(id, request, PrincipalContext.actorOr("api"));
    }

    @PostMapping("/integrations/{id}/provisioned-users")
    public ResponseEntity<ProvisionedUserResponse> initializeProvisionedUser(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ProvisionedUserRequest request) {
        return ResponseEntity.status(201).body(service.initializeProvisionedUser(id, request,
                requiredKey(idempotencyKey), PrincipalContext.actorOr("api")));
    }

    @PutMapping("/integrations/{id}/provisioned-users/{externalUserId}")
    public ProvisionedUserResponse updateProvisionedUser(@PathVariable UUID id,
            @PathVariable String externalUserId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam("externalOrganizationId") String externalOrganizationId,
            @RequestBody ProvisionedUserUpdateRequest request) {
        return service.updateProvisionedUser(id, externalOrganizationId, externalUserId, request, requiredKey(idempotencyKey),
                PrincipalContext.actorOr("api"));
    }

    @PostMapping("/integrations/{id}/provisioned-users/{externalUserId}/disable")
    public ProvisionedUserResponse disableProvisionedUser(@PathVariable UUID id,
            @PathVariable String externalUserId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam("externalOrganizationId") String externalOrganizationId) {
        return service.disableProvisionedUser(id, externalOrganizationId, externalUserId, requiredKey(idempotencyKey),
                PrincipalContext.actorOr("api"));
    }

    @GetMapping("/integrations/{id}/provisioned-users/{externalUserId}/memberships")
    public List<ProvisionedMembershipResponse> listProvisionedUserMemberships(@PathVariable UUID id,
            @PathVariable String externalUserId,
            @RequestParam("externalOrganizationId") String externalOrganizationId) {
        return service.listProvisionedUserMemberships(id, externalOrganizationId, externalUserId,
                PrincipalContext.actorOr("api"));
    }

    public record CreateOrganizationRequest(String name) { }
    public record CreateTenantRequest(String name) { }
    public record CreateIntegrationRequest(String name) { }
    public record CreateCredentialRequest(String label, String credentialRef) {
        public CreateCredentialRequest(String label) {
            this(label, null);
        }
    }
    public record CredentialVersionRequest(long expectedVersion, String credentialRef) {
        public CredentialVersionRequest(long expectedVersion) {
            this(expectedVersion, null);
        }
    }
    public record ProvisioningPolicyRequest(boolean allowAutoCreate, boolean allowPlatformAdmin, long expectedVersion) { }
    public record CreateUserRequest(String subject, String displayName) { }
    public record UserStatusRequest(long expectedVersion, String status) { }
    public record StatusVersionRequest(long expectedVersion, String status) { }
    public record MembershipRequest(String subject, String role) { }
    public record ExternalIdentityRequest(UUID organizationId, UUID internalUserId, String externalOrganizationId,
            String externalUserId) { }

    public record OrganizationResponse(UUID id, String name, String status, long version) { }
    public record TenantResponse(UUID id, UUID organizationId, String name, String status, long version) { }
    public record IntegrationResponse(UUID id, UUID organizationId, String name, String status, long version) { }
    public record CredentialResponse(UUID id, UUID integrationId, String label, String accessKeyId,
            long version, String status) { }
    public record ProvisioningPolicyResponse(UUID integrationId, boolean allowAutoCreate,
            boolean allowPlatformAdmin, long version) { }
    public record UserResponse(UUID id, String subject, String displayName, String status, long version) { }
    public record MembershipResponse(UUID organizationId, String subject, String role) { }
    public record ExternalIdentityResponse(UUID id, UUID integrationId, UUID organizationId, UUID internalUserId,
            String externalOrganizationId, String externalUserId, String status) { }

    public record ProvisionedUserRequest(String externalOrganizationId, String externalUserId, String displayName) { }
    public record ProvisionedUserUpdateRequest(String displayName) { }
    public record ProvisionedUserResponse(UUID integrationId, String externalOrganizationId, String externalUserId,
            String displayName, String status, UUID internalUserId) { }
    public record ProvisionedMembershipResponse(String scopeType, String scopeId, String scopeName, String role) { }

    private static String requiredKey(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
        if (value.length() > 255) throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        return value;
    }
}
