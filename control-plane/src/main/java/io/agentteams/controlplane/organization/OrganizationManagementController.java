package io.agentteams.controlplane.organization;

import io.agentteams.controlplane.security.PrincipalContext;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/management")
public final class OrganizationManagementController {
    private final OrganizationManagementService service;

    public OrganizationManagementController(OrganizationManagementService service) {
        this.service = service;
    }

    @PostMapping("/organizations")
    public ResponseEntity<OrganizationResponse> createOrganization(@RequestBody CreateOrganizationRequest request) {
        return ResponseEntity.status(201).body(service.createOrganization(request.name(), PrincipalContext.actorOr("api")));
    }

    @PostMapping("/organizations/{id}/tenants")
    public ResponseEntity<TenantResponse> createTenant(@PathVariable UUID id, @RequestBody CreateTenantRequest request) {
        return ResponseEntity.status(201).body(service.createTenant(id, request.name(), PrincipalContext.actorOr("api")));
    }

    @PostMapping("/organizations/{id}/integrations")
    public ResponseEntity<IntegrationResponse> createIntegration(@PathVariable UUID id,
            @RequestBody CreateIntegrationRequest request) {
        return ResponseEntity.status(201).body(service.createIntegration(id, request.name(), PrincipalContext.actorOr("api")));
    }

    @PostMapping("/integrations/{id}/credentials")
    public ResponseEntity<CredentialResponse> createCredential(@PathVariable UUID id,
            @RequestBody CreateCredentialRequest request) {
        return ResponseEntity.status(201).body(service.createCredential(id, request.label(), PrincipalContext.actorOr("api")));
    }

    @PostMapping("/credentials/{id}/rotate")
    public CredentialResponse rotateCredential(@PathVariable UUID id, @RequestBody CredentialVersionRequest request) {
        return service.rotateCredential(id, request.expectedVersion(), PrincipalContext.actorOr("api"));
    }

    @PostMapping("/credentials/{id}/revoke")
    public CredentialResponse revokeCredential(@PathVariable UUID id, @RequestBody CredentialVersionRequest request) {
        return service.revokeCredential(id, request.expectedVersion(), PrincipalContext.actorOr("api"));
    }

    @PutMapping("/integrations/{id}/provisioning-policy")
    public ProvisioningPolicyResponse updateProvisioningPolicy(@PathVariable UUID id,
            @RequestBody ProvisioningPolicyRequest request) {
        return service.updateProvisioningPolicy(id, request, PrincipalContext.actorOr("api"));
    }

    public record CreateOrganizationRequest(String name) { }
    public record CreateTenantRequest(String name) { }
    public record CreateIntegrationRequest(String name) { }
    public record CreateCredentialRequest(String label) { }
    public record CredentialVersionRequest(long expectedVersion) { }
    public record ProvisioningPolicyRequest(boolean allowAutoCreate, boolean allowPlatformAdmin, long expectedVersion) { }

    public record OrganizationResponse(UUID id, String name, String status, long version) { }
    public record TenantResponse(UUID id, UUID organizationId, String name, String status, long version) { }
    public record IntegrationResponse(UUID id, UUID organizationId, String name, String status, long version) { }
    public record CredentialResponse(UUID id, UUID integrationId, String label, String accessKeyId,
            String secret, long version, String status) { }
    public record ProvisioningPolicyResponse(UUID integrationId, boolean allowAutoCreate,
            boolean allowPlatformAdmin, long version) { }
}
