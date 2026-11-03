package io.agentteams.controlplane.organization;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ManagementRoleRepository {
    boolean hasRole(String subject, String scopeType, UUID organizationId, String role);
    boolean hasAnyPlatformAdmin();
    boolean bootstrapPlatformAdminIfAbsent(String subject, String actor, Instant now);
    List<ManagementRoleAssignment> list(String subject);
    List<ManagementRoleAssignment> listByScope(String scopeType, UUID organizationId);
    ManagementRoleAssignment upsert(String subject, String scopeType, UUID organizationId, String role,
            String actor, Instant now);
    boolean updateStatus(String subject, String scopeType, UUID organizationId, String status,
            long expectedVersion, String actor, Instant now);
    record ManagementRoleAssignment(UUID id, String subject, String scopeType, UUID organizationId,
            String role, String status, long version) { }
}
