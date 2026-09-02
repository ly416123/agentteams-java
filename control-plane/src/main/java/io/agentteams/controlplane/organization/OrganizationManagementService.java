package io.agentteams.controlplane.organization;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.CredentialReferenceValidator;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.SignatureAlgorithm;
import io.agentteams.controlplane.service.IdempotencyService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class OrganizationManagementService {
    private static final java.util.Set<String> ORGANIZATION_ROLES = java.util.Set.of("OWNER", "ADMIN", "MEMBER", "AUDITOR");
    private final OrganizationManagementRepository repository;
    private final Clock clock;
    private final IdempotencyService idempotency;
    private final AuditRecorder auditRecorder;
    private final Map<UUID, OrganizationManagementController.OrganizationResponse> organizations = new LinkedHashMap<>();
    private final Map<UUID, OrganizationManagementController.TenantResponse> tenants = new LinkedHashMap<>();
    private final Map<UUID, OrganizationManagementController.IntegrationResponse> integrations = new LinkedHashMap<>();
    private final Map<UUID, CredentialState> credentials = new LinkedHashMap<>();
    private final Map<String, CredentialReplay> credentialOperations = new LinkedHashMap<>();
    private final Map<UUID, OrganizationManagementController.ProvisioningPolicyResponse> policies = new LinkedHashMap<>();
    private final Map<String, OrganizationManagementController.ProvisionedUserResponse> provisionedUsers = new LinkedHashMap<>();

    /** Test-only compatibility constructor; production wiring uses the JDBC repository constructor. */
    OrganizationManagementService() {
        this.repository = null;
        this.clock = Clock.systemUTC();
        this.idempotency = new IdempotencyService();
        this.auditRecorder = event -> { };
    }

    /** Retained for focused service tests; production wiring uses the four-argument constructor. */
    public OrganizationManagementService(OrganizationManagementRepository repository, Clock clock,
            IdempotencyService idempotency) {
        this(repository, clock, idempotency, event -> { });
    }

    @Autowired
    public OrganizationManagementService(OrganizationManagementRepository repository, Clock clock,
            IdempotencyService idempotency, AuditRecorder auditRecorder) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
    }

    public OrganizationManagementController.OrganizationResponse createOrganization(String name, String actor) {
        require("platform:organization:create");
        return createOrganizationLegacy(name);
    }

    public OrganizationManagementController.OrganizationResponse createOrganization(String name, String idempotencyKey,
            String actor) {
        require("platform:organization:create");
        String normalizedName = required(name, "name");
        String key = idempotency.requireKey(idempotencyKey);
        String requestHash = idempotency.requestHash("CREATE_ORGANIZATION", normalizedName);
        UUID id = UUID.randomUUID();
        if (repository != null) {
            return repository.insertOrganizationIdempotent(id, externalKey(normalizedName, id), normalizedName, key,
                    requestHash, clock.instant());
        }
        return createOrganizationLegacy(normalizedName);
    }

    public java.util.List<OrganizationManagementController.OrganizationResponse> listOrganizations() {
        require("platform:organization:read");
        if (repository != null) return repository.listOrganizations();
        return java.util.List.copyOf(organizations.values());
    }

    public java.util.List<OrganizationManagementController.TenantResponse> listTenants(UUID organizationId) {
        require("organization:admin"); ensureOrganization(organizationId);
        if (repository != null) return repository.listTenants(organizationId);
        return tenants.values().stream().filter(value -> value.organizationId().equals(organizationId)).toList();
    }

    public OrganizationManagementController.TenantResponse createTenant(UUID organizationId, String name, String actor) {
        require("organization:admin");
        return createTenantLegacy(organizationId, name);
    }

    public OrganizationManagementController.TenantResponse createTenant(UUID organizationId, String name,
            String idempotencyKey, String actor) {
        require("organization:admin");
        ensureOrganization(organizationId);
        String normalizedName = required(name, "name");
        String key = idempotency.requireKey(idempotencyKey);
        String requestHash = idempotency.requestHash("CREATE_TENANT", organizationId.toString(), normalizedName);
        UUID id = UUID.randomUUID();
        if (repository != null) {
            return repository.insertTenantIdempotent(id, organizationId, externalKey(normalizedName, id), normalizedName,
                    key, requestHash, clock.instant());
        }
        return createTenantLegacy(organizationId, normalizedName);
    }

    public OrganizationManagementController.OrganizationResponse updateOrganizationStatus(UUID id,
            long expectedVersion, String status, String actor) {
        requireAny("organization:write", "organization:admin", "platform:organization:create");
        ensureOrganization(id);
        String normalizedStatus = status(status);
        if (repository != null) {
            return repository.updateOrganizationStatus(id, expectedVersion, normalizedStatus, clock.instant());
        }
        var current = organizations.get(id);
        if (current.version() != expectedVersion) throw new AuthorizationException("organization version conflict");
        var updated = new OrganizationManagementController.OrganizationResponse(id, current.name(), normalizedStatus,
                expectedVersion + 1);
        organizations.put(id, updated);
        return updated;
    }

    public OrganizationManagementController.TenantResponse updateTenantStatus(UUID id, long expectedVersion,
            String status, String actor) {
        requireAny("organization:write", "organization:admin");
        String normalizedStatus = status(status);
        if (repository != null) {
            return repository.updateTenantStatus(id, expectedVersion, normalizedStatus, clock.instant());
        }
        var current = tenants.get(id);
        if (current == null) throw new IllegalArgumentException("tenant not found");
        if (current.version() != expectedVersion) throw new AuthorizationException("tenant version conflict");
        var updated = new OrganizationManagementController.TenantResponse(id, current.organizationId(), current.name(),
                normalizedStatus, expectedVersion + 1);
        tenants.put(id, updated);
        return updated;
    }

    private OrganizationManagementController.OrganizationResponse createOrganizationLegacy(String name) {
        UUID id = UUID.randomUUID();
        if (repository != null) {
            return repository.insertOrganization(id, externalKey(name, id), required(name, "name"), clock.instant());
        }
        var response = new OrganizationManagementController.OrganizationResponse(id, required(name, "name"), "ACTIVE", 0);
        organizations.put(id, response);
        return response;
    }

    private OrganizationManagementController.TenantResponse createTenantLegacy(UUID organizationId, String name) {
        ensureOrganization(organizationId);
        UUID id = UUID.randomUUID();
        if (repository != null) {
            return repository.insertTenant(id, organizationId, externalKey(name, id), required(name, "name"), clock.instant());
        }
        var response = new OrganizationManagementController.TenantResponse(id, organizationId, required(name, "name"),
                "ACTIVE", 0);
        tenants.put(id, response);
        return response;
    }

    public OrganizationManagementController.IntegrationResponse createIntegration(UUID organizationId, String name, String actor) {
        require("integration:manage");
        ensureOrganization(organizationId);
        UUID id = UUID.randomUUID();
        if (repository != null) {
            var response = repository.insertIntegration(id, organizationId, externalKey(name, id), required(name, "name"),
                    clock.instant());
            repository.insertDefaultProvisioningPolicy(id, clock.instant());
            return response;
        }
        var response = new OrganizationManagementController.IntegrationResponse(id, organizationId, required(name, "name"),
                "ACTIVE", 0);
        integrations.put(id, response);
        policies.put(id, new OrganizationManagementController.ProvisioningPolicyResponse(id, false, false, 0));
        return response;
    }

    public java.util.List<OrganizationManagementController.IntegrationResponse> listIntegrations(UUID organizationId) {
        require("integration:manage"); ensureOrganization(organizationId);
        if (repository != null) return repository.listIntegrations(organizationId);
        return integrations.values().stream().filter(value -> value.organizationId().equals(organizationId)).toList();
    }

    public OrganizationManagementController.CredentialResponse createCredential(UUID integrationId, String label, String actor) {
        return createCredential(integrationId, label, null, actor);
    }

    public OrganizationManagementController.CredentialResponse createCredential(UUID integrationId, String label,
            String credentialRef, String actor) {
        require("credential:manage");
        ensureIntegration(integrationId);
        UUID id = UUID.randomUUID();
        if (repository != null) {
            String normalizedRef = CredentialReferenceValidator.normalize(credentialRef);
            if (normalizedRef == null) {
                throw new IllegalArgumentException("credentialRef is required");
            }
            return repository.insertCredential(id, integrationId, required(label, "label"), newAccessKeyId(),
                    normalizedRef, SignatureAlgorithm.HMAC_SHA256, null, clock.instant());
        }
        var state = new CredentialState(id, integrationId, required(label, "label"),
                newAccessKeyId(), newSecret(), 1, "ACTIVE");
        credentials.put(id, state);
        return state.response();
    }

    public OrganizationManagementController.CredentialResponse createCredential(UUID integrationId, String label,
            String credentialRef, String idempotencyKey, String actor) {
        require("credential:manage");
        ensureIntegration(integrationId);
        String normalizedLabel = required(label, "label");
        String normalizedRef = CredentialReferenceValidator.normalize(credentialRef);
        if (normalizedRef == null) throw new IllegalArgumentException("credentialRef is required");
        String key = idempotency.requireKey(idempotencyKey);
        String requestHash = idempotency.requestHash("CREATE_MANAGEMENT_CREDENTIAL", integrationId.toString(),
                normalizedLabel, normalizedRef);
        if (repository != null) {
            var response = repository.insertCredentialIdempotent(UUID.randomUUID(), integrationId, normalizedLabel,
                    newAccessKeyId(), normalizedRef, SignatureAlgorithm.HMAC_SHA256, null, key, requestHash,
                    clock.instant());
            recordCredentialAudit(actor, "CREDENTIAL_CREATED", response);
            return response;
        }
        synchronized (credentials) {
            var replay = credentialOperations.get(key);
            if (replay != null) {
                assertCredentialReplay(replay, "CREATE_MANAGEMENT_CREDENTIAL", requestHash, key);
                return replay.response();
            }
            var response = createCredentialLegacy(integrationId, normalizedLabel, normalizedRef);
            credentialOperations.put(key, new CredentialReplay("CREATE_MANAGEMENT_CREDENTIAL", requestHash, response));
            recordCredentialAudit(actor, "CREDENTIAL_CREATED", response);
            return response;
        }
    }

    public OrganizationManagementController.CredentialResponse rotateCredential(UUID credentialId, long expectedVersion,
            String actor) {
        return rotateCredential(credentialId, expectedVersion, null, actor);
    }

    public OrganizationManagementController.CredentialResponse rotateCredential(UUID credentialId, long expectedVersion,
            String credentialRef, String actor) {
        require("credential:manage");
        if (repository != null) {
            var current = repository.findCredential(Objects.requireNonNull(credentialId, "credentialId"))
                    .orElseThrow(() -> new IllegalArgumentException("credential not found"));
            String normalizedRef = CredentialReferenceValidator.normalize(credentialRef);
            if (normalizedRef == null) {
                throw new IllegalArgumentException("credentialRef is required for rotation");
            }
            var rotated = repository.updateCredential(credentialId, expectedVersion, newAccessKeyId(), normalizedRef,
                    current.status(), current.expiresAt(), clock.instant()).orElseThrow();
            return credentialResponse(rotated);
        }
        synchronized (credentials) {
            CredentialState current = credential(credentialId);
            if (current.version() != expectedVersion) {
                throw new AuthorizationException("credential version conflict");
            }
            var rotated = new CredentialState(current.id(), current.integrationId(), current.label(), newAccessKeyId(),
                    newSecret(), current.version() + 1, current.status());
            credentials.put(current.id(), rotated);
            return rotated.response();
        }
    }

    public OrganizationManagementController.CredentialResponse rotateCredential(UUID credentialId, long expectedVersion,
            String idempotencyKey, String credentialRef, String actor) {
        require("credential:manage");
        String key = idempotency.requireKey(idempotencyKey);
        String normalizedRef = CredentialReferenceValidator.normalize(credentialRef);
        if (normalizedRef == null) throw new IllegalArgumentException("credentialRef is required for rotation");
        String requestHash = idempotency.requestHash("ROTATE_MANAGEMENT_CREDENTIAL", credentialId.toString(),
                Long.toString(expectedVersion), normalizedRef);
        if (repository != null) {
            var current = repository.findCredential(Objects.requireNonNull(credentialId, "credentialId"))
                    .orElseThrow(() -> new IllegalArgumentException("credential not found"));
            var response = repository.updateCredentialIdempotent(credentialId, expectedVersion, newAccessKeyId(),
                    normalizedRef, current.status(), current.expiresAt(), "ROTATE_MANAGEMENT_CREDENTIAL", key,
                    requestHash, clock.instant());
            recordCredentialAudit(actor, "CREDENTIAL_ROTATED", response);
            return response;
        }
        synchronized (credentials) {
            var replay = credentialOperations.get(key);
            if (replay != null) {
                assertCredentialReplay(replay, "ROTATE_MANAGEMENT_CREDENTIAL", requestHash, key);
                return replay.response();
            }
            CredentialState current = credential(credentialId);
            if (current.version() != expectedVersion) throw new AuthorizationException("credential version conflict");
            var rotated = new CredentialState(current.id(), current.integrationId(), current.label(), newAccessKeyId(),
                    newSecret(), current.version() + 1, current.status());
            credentials.put(current.id(), rotated);
            var response = rotated.response();
            credentialOperations.put(key, new CredentialReplay("ROTATE_MANAGEMENT_CREDENTIAL", requestHash, response));
            recordCredentialAudit(actor, "CREDENTIAL_ROTATED", response);
            return response;
        }
    }

    public OrganizationManagementController.CredentialResponse revokeCredential(UUID credentialId, long expectedVersion,
            String actor) {
        require("credential:manage");
        if (repository != null) {
            var current = repository.findCredential(Objects.requireNonNull(credentialId, "credentialId"))
                    .orElseThrow(() -> new IllegalArgumentException("credential not found"));
            var revoked = repository.updateCredential(credentialId, expectedVersion, current.accessKeyId(),
                    current.credentialRef(), "REVOKED", current.expiresAt(), clock.instant()).orElseThrow();
            return credentialResponse(revoked);
        }
        synchronized (credentials) {
            CredentialState current = credential(credentialId);
            if (current.version() != expectedVersion) {
                throw new AuthorizationException("credential version conflict");
            }
            var revoked = new CredentialState(current.id(), current.integrationId(), current.label(), current.accessKeyId(),
                    null, current.version() + 1, "REVOKED");
            credentials.put(current.id(), revoked);
            return revoked.response();
        }
    }

    public OrganizationManagementController.CredentialResponse revokeCredential(UUID credentialId, long expectedVersion,
            String idempotencyKey, String actor) {
        require("credential:manage");
        String key = idempotency.requireKey(idempotencyKey);
        String requestHash = idempotency.requestHash("REVOKE_MANAGEMENT_CREDENTIAL", credentialId.toString(),
                Long.toString(expectedVersion));
        if (repository != null) {
            var current = repository.findCredential(Objects.requireNonNull(credentialId, "credentialId"))
                    .orElseThrow(() -> new IllegalArgumentException("credential not found"));
            var response = repository.updateCredentialIdempotent(credentialId, expectedVersion, current.accessKeyId(),
                    current.credentialRef(), "REVOKED", current.expiresAt(), "REVOKE_MANAGEMENT_CREDENTIAL", key,
                    requestHash, clock.instant());
            recordCredentialAudit(actor, "CREDENTIAL_REVOKED", response);
            return response;
        }
        synchronized (credentials) {
            var replay = credentialOperations.get(key);
            if (replay != null) {
                assertCredentialReplay(replay, "REVOKE_MANAGEMENT_CREDENTIAL", requestHash, key);
                return replay.response();
            }
            CredentialState current = credential(credentialId);
            if (current.version() != expectedVersion) throw new AuthorizationException("credential version conflict");
            var revoked = new CredentialState(current.id(), current.integrationId(), current.label(), current.accessKeyId(),
                    null, current.version() + 1, "REVOKED");
            credentials.put(current.id(), revoked);
            var response = revoked.response();
            credentialOperations.put(key, new CredentialReplay("REVOKE_MANAGEMENT_CREDENTIAL", requestHash, response));
            recordCredentialAudit(actor, "CREDENTIAL_REVOKED", response);
            return response;
        }
    }

    public java.util.List<OrganizationManagementController.CredentialResponse> listCredentials(UUID integrationId) {
        require("credential:manage"); ensureIntegration(integrationId);
        if (repository != null) return repository.listCredentials(integrationId);
        return credentials.values().stream().filter(value -> value.integrationId().equals(integrationId))
                .map(CredentialState::response).toList();
    }

    public OrganizationManagementController.ProvisioningPolicyResponse updateProvisioningPolicy(UUID integrationId,
            OrganizationManagementController.ProvisioningPolicyRequest request, String actor) {
        require("provisioning-policy:manage");
        ensureIntegration(integrationId);
        if (repository != null) {
            return repository.updateProvisioningPolicy(integrationId, request.allowAutoCreate(),
                    request.allowPlatformAdmin(), request.expectedVersion(), clock.instant());
        }
        var current = policies.get(integrationId);
        if (current != null && current.version() != request.expectedVersion()) {
            throw new AuthorizationException("provisioning policy version conflict");
        }
        var updated = new OrganizationManagementController.ProvisioningPolicyResponse(integrationId,
                request.allowAutoCreate(), request.allowPlatformAdmin(), request.expectedVersion() + 1);
        policies.put(integrationId, updated);
        return updated;
    }

    public OrganizationManagementController.UserResponse createUser(String subject, String displayName, String actor) {
        require("user:manage");
        String normalizedSubject = required(subject, "subject");
        String normalizedName = required(displayName, "displayName");
        if (repository == null) {
            UUID id = UUID.randomUUID();
            return new OrganizationManagementController.UserResponse(id, normalizedSubject, normalizedName, "ACTIVE", 0);
        }
        return repository.insertUser(UUID.randomUUID(), normalizedSubject, normalizedName, clock.instant());
    }

    public java.util.List<OrganizationManagementController.UserResponse> listUsers() {
        require("user:manage");
        return repository == null ? java.util.List.of() : repository.listUsers();
    }

    public OrganizationManagementController.UserResponse updateUserStatus(UUID id, long expectedVersion, String status,
            String actor) {
        require("user:manage");
        if (repository == null) throw new UnsupportedOperationException("user status requires persistent repository");
        return repository.updateUserStatus(id, expectedVersion, required(status, "status").toUpperCase(java.util.Locale.ROOT),
                clock.instant());
    }

    public java.util.List<OrganizationManagementController.MembershipResponse> listOrganizationMemberships(UUID id) {
        require("organization:admin"); ensureOrganization(id);
        return repository == null ? java.util.List.of() : repository.listOrganizationMemberships(id);
    }

    public OrganizationManagementController.MembershipResponse upsertOrganizationMembership(UUID organizationId,
            String subject, String role, String actor) {
        require("organization:admin");
        ensureOrganization(organizationId);
        String normalizedSubject = required(subject, "subject");
        String normalizedRole = required(role, "role").toUpperCase(java.util.Locale.ROOT);
        if (!ORGANIZATION_ROLES.contains(normalizedRole)) throw new IllegalArgumentException("unsupported organization role");
        if (repository == null) {
            return new OrganizationManagementController.MembershipResponse(organizationId, normalizedSubject, normalizedRole);
        }
        return repository.upsertOrganizationMembership(organizationId, normalizedSubject, normalizedRole, clock.instant());
    }

    public OrganizationManagementController.ExternalIdentityResponse upsertExternalIdentity(UUID integrationId,
            OrganizationManagementController.ExternalIdentityRequest request, String actor) {
        require("external-user:manage");
        var integration = repository == null ? null : repository.findIntegration(Objects.requireNonNull(integrationId, "integrationId"))
                .orElseThrow(() -> new IllegalArgumentException("integration not found"));
        if (repository == null) ensureIntegration(integrationId);
        String externalOrganizationId = required(request.externalOrganizationId(), "externalOrganizationId");
        String externalUserId = required(request.externalUserId(), "externalUserId");
        Objects.requireNonNull(request.internalUserId(), "internalUserId");
        UUID organizationId = integration == null ? request.organizationId() : integration.organizationId();
        if (organizationId == null) throw new IllegalArgumentException("organizationId is required");
        if (repository == null) {
            return new OrganizationManagementController.ExternalIdentityResponse(UUID.randomUUID(), integrationId,
                    organizationId, request.internalUserId(), externalOrganizationId, externalUserId, "ACTIVE");
        }
        return repository.upsertExternalIdentity(UUID.randomUUID(), integrationId, organizationId, request.internalUserId(),
                externalOrganizationId, externalUserId, clock.instant());
    }

    public java.util.List<OrganizationManagementController.ExternalIdentityResponse> listExternalIdentities(UUID id) {
        require("external-user:manage"); ensureIntegration(id);
        return repository == null ? java.util.List.of() : repository.listExternalIdentities(id);
    }

    public OrganizationManagementController.ProvisionedUserResponse initializeProvisionedUser(UUID integrationId,
            OrganizationManagementController.ProvisionedUserRequest request, String idempotencyKey, String actor) {
        require("external-user:manage");
        ensureIntegration(integrationId);
        Objects.requireNonNull(request, "request");
        String externalOrganizationId = required(request.externalOrganizationId(), "externalOrganizationId");
        String externalUserId = required(request.externalUserId(), "externalUserId");
        String displayName = required(request.displayName(), "displayName");
        String key = idempotency.requireKey(idempotencyKey);
        String requestHash = idempotency.requestHash("PROVISION_EXTERNAL_USER", integrationId.toString(),
                externalOrganizationId, externalUserId, displayName);
        OrganizationManagementController.ProvisionedUserResponse response;
        if (repository != null) {
            UUID organizationId = repository.findIntegration(integrationId).orElseThrow().organizationId();
            response = repository.provisionExternalUser(integrationId, organizationId, externalOrganizationId, externalUserId,
                    displayName, key, requestHash, clock.instant());
        } else {
            String mapKey = provisionedKey(integrationId, externalOrganizationId, externalUserId);
            var existing = provisionedUsers.get(mapKey);
            if (existing != null) {
                response = existing;
            } else {
                response = new OrganizationManagementController.ProvisionedUserResponse(integrationId,
                        externalOrganizationId, externalUserId, displayName, "ACTIVE", UUID.randomUUID());
                provisionedUsers.put(mapKey, response);
            }
        }
        recordProvisioningAudit(actor, "EXTERNAL_USER_INITIALIZED", response);
        return response;
    }

    public OrganizationManagementController.ProvisionedUserResponse updateProvisionedUser(UUID integrationId,
            String externalOrganizationId, String externalUserId,
            OrganizationManagementController.ProvisionedUserUpdateRequest request,
            String idempotencyKey, String actor) {
        require("external-user:manage");
        ensureIntegration(integrationId);
        String targetExternalOrganizationId = required(externalOrganizationId, "externalOrganizationId");
        String targetExternalUserId = required(externalUserId, "externalUserId");
        Objects.requireNonNull(request, "request");
        String displayName = required(request.displayName(), "displayName");
        var current = findProvisionedUser(integrationId, targetExternalOrganizationId, targetExternalUserId);
        String key = idempotency.requireKey(idempotencyKey);
        String requestHash = idempotency.requestHash("UPDATE_PROVISIONED_USER", integrationId.toString(),
                targetExternalOrganizationId, targetExternalUserId, displayName);
        OrganizationManagementController.ProvisionedUserResponse response;
        if (repository != null) {
            response = repository.updateProvisionedUser(integrationId, targetExternalOrganizationId, targetExternalUserId,
                    displayName, key, requestHash, clock.instant());
        } else {
            response = new OrganizationManagementController.ProvisionedUserResponse(integrationId,
                    targetExternalOrganizationId, targetExternalUserId, displayName, current.status(),
                    current.internalUserId());
            provisionedUsers.put(provisionedKey(integrationId, targetExternalOrganizationId, targetExternalUserId), response);
        }
        recordProvisioningAudit(actor, "EXTERNAL_USER_UPDATED", response);
        return response;
    }

    public OrganizationManagementController.ProvisionedUserResponse disableProvisionedUser(UUID integrationId,
            String externalOrganizationId, String externalUserId, String idempotencyKey, String actor) {
        require("external-user:manage");
        ensureIntegration(integrationId);
        String targetExternalOrganizationId = required(externalOrganizationId, "externalOrganizationId");
        String targetExternalUserId = required(externalUserId, "externalUserId");
        var current = findProvisionedUser(integrationId, targetExternalOrganizationId, targetExternalUserId);
        String key = idempotency.requireKey(idempotencyKey);
        String requestHash = idempotency.requestHash("DISABLE_PROVISIONED_USER", integrationId.toString(),
                targetExternalOrganizationId, targetExternalUserId);
        OrganizationManagementController.ProvisionedUserResponse response;
        if (repository != null) {
            response = repository.disableProvisionedUser(integrationId, targetExternalOrganizationId, targetExternalUserId,
                    key, requestHash, clock.instant());
        } else {
            response = new OrganizationManagementController.ProvisionedUserResponse(integrationId,
                    targetExternalOrganizationId, targetExternalUserId, current.displayName(), "DISABLED",
                    current.internalUserId());
            provisionedUsers.put(provisionedKey(integrationId, targetExternalOrganizationId, targetExternalUserId), response);
        }
        recordProvisioningAudit(actor, "EXTERNAL_USER_DISABLED", response);
        return response;
    }

    public java.util.List<OrganizationManagementController.ProvisionedMembershipResponse> listProvisionedUserMemberships(
            UUID integrationId, String externalOrganizationId, String externalUserId, String actor) {
        require("external-user:manage");
        ensureIntegration(integrationId);
        String externalOrganization = required(externalOrganizationId, "externalOrganizationId");
        String externalUser = required(externalUserId, "externalUserId");
        if (repository != null) {
            UUID organizationId = repository.findIntegration(integrationId).orElseThrow().organizationId();
            return repository.listProvisionedUserMemberships(integrationId, organizationId, externalOrganization, externalUser);
        }
        findProvisionedUser(integrationId, externalOrganization, externalUser);
        return java.util.List.of();
    }

    private OrganizationManagementController.ProvisionedUserResponse findProvisionedUser(UUID integrationId,
            String externalOrganizationId, String externalUserId) {
        if (repository != null) {
            return repository.listExternalIdentities(integrationId).stream()
                    .filter(identity -> identity.externalOrganizationId().equals(externalOrganizationId)
                            && identity.externalUserId().equals(externalUserId))
                    .findFirst()
                    .map(identity -> new OrganizationManagementController.ProvisionedUserResponse(integrationId,
                            identity.externalOrganizationId(), identity.externalUserId(), "", identity.status(),
                            identity.internalUserId()))
                    .orElseThrow(() -> new IllegalArgumentException("provisioned user not found"));
        }
        return provisionedUsers.values().stream()
                .filter(user -> user.integrationId().equals(integrationId) && user.externalUserId().equals(externalUserId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("provisioned user not found"));
    }

    private static String provisionedKey(UUID integrationId, String externalOrganizationId, String externalUserId) {
        return integrationId + "\u001f" + externalOrganizationId + "\u001f" + externalUserId;
    }

    private void recordProvisioningAudit(String actor, String action,
            OrganizationManagementController.ProvisionedUserResponse response) {
        auditRecorder.record(new AuditEvent(
                UUID.randomUUID(),
                actor,
                action,
                "external_identity",
                response.internalUserId().toString(),
                Map.of(
                        "integrationId", response.integrationId().toString(),
                        "externalOrganizationId", response.externalOrganizationId(),
                        "externalUserId", response.externalUserId(),
                        "status", response.status()),
                clock.instant()));
    }

    private OrganizationManagementController.CredentialResponse createCredentialLegacy(UUID integrationId, String label,
            String credentialRef) {
        UUID id = UUID.randomUUID();
        var state = new CredentialState(id, integrationId, label, newAccessKeyId(), newSecret(), 1, "ACTIVE");
        credentials.put(id, state);
        return state.response();
    }

    private static void assertCredentialReplay(CredentialReplay replay, String operation, String requestHash,
            String key) {
        if (!operation.equals(replay.operation()) || !requestHash.equals(replay.requestHash())) {
            throw new IllegalArgumentException("idempotency key was reused with a different request: " + key);
        }
    }

    private void recordCredentialAudit(String actor, String action,
            OrganizationManagementController.CredentialResponse response) {
        auditRecorder.record(new AuditEvent(
                UUID.randomUUID(),
                actor,
                action,
                "integration_credential",
                response.id().toString(),
                Map.of(
                        "integrationId", response.integrationId().toString(),
                        "status", response.status(),
                        "version", Long.toString(response.version())),
                clock.instant()));
    }

    private void require(String permission) {
        var principal = PrincipalContext.current().orElseThrow(() -> new AuthorizationException("actor is required"));
        if (!principal.permissions().contains(permission)) {
            throw new AuthorizationException("permission denied: " + permission);
        }
    }

    private void requireAny(String... permissions) {
        var principal = PrincipalContext.current().orElseThrow(() -> new AuthorizationException("actor is required"));
        for (String permission : permissions) {
            if (principal.permissions().contains(permission)) return;
        }
        throw new AuthorizationException("permission denied: " + String.join(" or ", permissions));
    }

    private static String status(String value) {
        String normalized = required(value, "status").toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("ACTIVE", "SUSPENDED", "DELETED").contains(normalized)) {
            throw new IllegalArgumentException("status must be ACTIVE, SUSPENDED or DELETED");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private void ensureOrganization(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        if (repository != null) {
            if (repository.findOrganization(organizationId).isEmpty()) throw new IllegalArgumentException("organization not found");
            return;
        }
        if (!organizations.containsKey(organizationId)) {
            throw new IllegalArgumentException("organization not found");
        }
    }

    private void ensureIntegration(UUID integrationId) {
        Objects.requireNonNull(integrationId, "integrationId");
        if (repository != null) {
            if (repository.findIntegration(integrationId).isEmpty()) throw new IllegalArgumentException("integration not found");
            return;
        }
        if (!integrations.containsKey(integrationId)) {
            throw new IllegalArgumentException("integration not found");
        }
    }

    private CredentialState credential(UUID credentialId) {
        CredentialState current = credentials.get(Objects.requireNonNull(credentialId, "credentialId"));
        if (current == null) throw new IllegalArgumentException("credential not found");
        return current;
    }

    private static String newAccessKeyId() {
        return "AKIA-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String newSecret() {
        return "secret-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String externalKey(String displayName, UUID id) {
        String slug = displayName.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return (slug.isBlank() ? "resource" : slug) + "-" + id.toString().substring(0, 8);
    }

    private static OrganizationManagementController.CredentialResponse credentialResponse(
            OrganizationManagementRepository.CredentialRecord credential) {
        return new OrganizationManagementController.CredentialResponse(credential.id(), credential.integrationId(),
                credential.label(), credential.accessKeyId(), credential.version(), credential.status());
    }

    private record CredentialState(UUID id, UUID integrationId, String label, String accessKeyId,
            String secret, long version, String status) {
        private OrganizationManagementController.CredentialResponse response() {
            return new OrganizationManagementController.CredentialResponse(id, integrationId, label, accessKeyId,
                    version, status);
        }

        @Override
        public String toString() {
            return "CredentialState[id=" + id + ", integrationId=" + integrationId + ", label=" + label
                    + ", accessKeyId=" + accessKeyId + ", version=" + version + ", status=" + status + "]";
        }
    }

    private record CredentialReplay(String operation, String requestHash,
            OrganizationManagementController.CredentialResponse response) { }
}
