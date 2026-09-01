package io.agentteams.controlplane.organization;

import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.PrincipalContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class OrganizationManagementService {
    private final Map<UUID, OrganizationManagementController.OrganizationResponse> organizations = new LinkedHashMap<>();
    private final Map<UUID, OrganizationManagementController.TenantResponse> tenants = new LinkedHashMap<>();
    private final Map<UUID, OrganizationManagementController.IntegrationResponse> integrations = new LinkedHashMap<>();
    private final Map<UUID, OrganizationManagementController.CredentialResponse> credentials = new LinkedHashMap<>();
    private final Map<UUID, OrganizationManagementController.ProvisioningPolicyResponse> policies = new LinkedHashMap<>();

    public OrganizationManagementController.OrganizationResponse createOrganization(String name, String actor) {
        require("platform:organization:create");
        UUID id = UUID.randomUUID();
        var response = new OrganizationManagementController.OrganizationResponse(id, required(name, "name"), "ACTIVE", 0);
        organizations.put(id, response);
        return response;
    }

    public OrganizationManagementController.TenantResponse createTenant(UUID organizationId, String name, String actor) {
        require("organization:admin");
        ensureOrganization(organizationId);
        UUID id = UUID.randomUUID();
        var response = new OrganizationManagementController.TenantResponse(id, organizationId, required(name, "name"),
                "ACTIVE", 0);
        tenants.put(id, response);
        return response;
    }

    public OrganizationManagementController.IntegrationResponse createIntegration(UUID organizationId, String name, String actor) {
        require("integration:manage");
        ensureOrganization(organizationId);
        UUID id = UUID.randomUUID();
        var response = new OrganizationManagementController.IntegrationResponse(id, organizationId, required(name, "name"),
                "ACTIVE", 0);
        integrations.put(id, response);
        policies.put(id, new OrganizationManagementController.ProvisioningPolicyResponse(id, false, false, 0));
        return response;
    }

    public OrganizationManagementController.CredentialResponse createCredential(UUID integrationId, String label, String actor) {
        require("credential:manage");
        ensureIntegration(integrationId);
        UUID id = UUID.randomUUID();
        var response = new OrganizationManagementController.CredentialResponse(id, integrationId, required(label, "label"),
                "AKIA-" + id.toString().substring(0, 8), "secret-" + id.toString().substring(0, 8), 1, "ACTIVE");
        credentials.put(id, response);
        return response;
    }

    public OrganizationManagementController.CredentialResponse rotateCredential(UUID credentialId, long expectedVersion,
            String actor) {
        require("credential:manage");
        OrganizationManagementController.CredentialResponse current = credential(credentialId);
        if (current.version() != expectedVersion) {
            throw new AuthorizationException("credential version conflict");
        }
        var rotated = new OrganizationManagementController.CredentialResponse(current.id(), current.integrationId(),
                current.label(), current.accessKeyId(), null, current.version() + 1, current.status());
        credentials.put(current.id(), rotated);
        return rotated;
    }

    public OrganizationManagementController.CredentialResponse revokeCredential(UUID credentialId, long expectedVersion,
            String actor) {
        require("credential:manage");
        OrganizationManagementController.CredentialResponse current = credential(credentialId);
        if (current.version() != expectedVersion) {
            throw new AuthorizationException("credential version conflict");
        }
        var revoked = new OrganizationManagementController.CredentialResponse(current.id(), current.integrationId(),
                current.label(), current.accessKeyId(), null, current.version() + 1, "REVOKED");
        credentials.put(current.id(), revoked);
        return revoked;
    }

    public OrganizationManagementController.ProvisioningPolicyResponse updateProvisioningPolicy(UUID integrationId,
            OrganizationManagementController.ProvisioningPolicyRequest request, String actor) {
        require("provisioning-policy:manage");
        ensureIntegration(integrationId);
        var current = policies.get(integrationId);
        if (current != null && current.version() != request.expectedVersion()) {
            throw new AuthorizationException("provisioning policy version conflict");
        }
        var updated = new OrganizationManagementController.ProvisioningPolicyResponse(integrationId,
                request.allowAutoCreate(), request.allowPlatformAdmin(), request.expectedVersion() + 1);
        policies.put(integrationId, updated);
        return updated;
    }

    private void require(String permission) {
        var principal = PrincipalContext.current().orElseThrow(() -> new AuthorizationException("actor is required"));
        if (!principal.permissions().contains(permission)) {
            throw new AuthorizationException("permission denied: " + permission);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private void ensureOrganization(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        if (!organizations.containsKey(organizationId)) {
            throw new IllegalArgumentException("organization not found");
        }
    }

    private void ensureIntegration(UUID integrationId) {
        Objects.requireNonNull(integrationId, "integrationId");
        if (!integrations.containsKey(integrationId)) {
            throw new IllegalArgumentException("integration not found");
        }
    }

    private OrganizationManagementController.CredentialResponse credential(UUID credentialId) {
        OrganizationManagementController.CredentialResponse current = credentials.get(Objects.requireNonNull(credentialId, "credentialId"));
        if (current == null) throw new IllegalArgumentException("credential not found");
        return current;
    }
}
