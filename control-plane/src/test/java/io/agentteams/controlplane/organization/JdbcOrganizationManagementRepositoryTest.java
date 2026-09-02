package io.agentteams.controlplane.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.OptimisticLockFailure;
import io.agentteams.controlplane.security.SignatureAlgorithm;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcOrganizationManagementRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void credentialInsertPersistsOnlyASecretReference() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        UUID credentialId = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();

        new JdbcOrganizationManagementRepository(jdbc).insertCredential(credentialId, integrationId, "sdk",
                "atk-1", "k8s://prod/agentteams#sdk-secret", SignatureAlgorithm.HMAC_SHA256, null, NOW);

        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("credential_ref")
                        && !sql.contains("access_key_secret") && !sql.contains("secret TEXT")),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void credentialUpdateUsesDatabaseVersionPredicate() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID credentialId = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();
        var current = new OrganizationManagementRepository.CredentialRecord(credentialId, integrationId, "sdk",
                "atk-1", "k8s://prod/agentteams#old", SignatureAlgorithm.HMAC_SHA256, "ACTIVE", null, 3);
        var updated = new OrganizationManagementRepository.CredentialRecord(credentialId, integrationId, "sdk",
                "atk-2", "k8s://prod/agentteams#new", SignatureAlgorithm.HMAC_SHA256, "ACTIVE", null, 4);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(credentialId), eq(3L))).thenReturn(List.of(current));
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(credentialId))).thenReturn(List.of(updated));

        var result = new JdbcOrganizationManagementRepository(jdbc).updateCredential(credentialId, 3, "atk-2",
                "k8s://prod/agentteams#new", "ACTIVE", null, NOW);

        assertThat(result).contains(updated);
        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("version = version + 1")
                        && sql.contains("WHERE id = ? AND version = ?")),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void credentialUpdateRejectsStaleVersionBeforeWriting() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID credentialId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(credentialId), eq(2L))).thenReturn(List.of());
        when(jdbc.query(anyString(), any(RowMapper.class), eq(credentialId))).thenReturn(List.of());

        assertThatThrownBy(() -> new JdbcOrganizationManagementRepository(jdbc).updateCredential(credentialId, 2,
                "atk-2", "k8s://prod/agentteams#new", "ACTIVE", null, NOW))
                .isInstanceOf(OptimisticLockFailure.class);
    }

    @Test
    void credentialUpdateRejectsWhenConcurrentDatabaseUpdateWins() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID credentialId = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();
        var current = new OrganizationManagementRepository.CredentialRecord(credentialId, integrationId, "sdk",
                "atk-1", "k8s://prod/agentteams#old", SignatureAlgorithm.HMAC_SHA256, "ACTIVE", null, 3);
        var winner = new OrganizationManagementRepository.CredentialRecord(credentialId, integrationId, "sdk",
                "atk-winner", "k8s://prod/agentteams#winner", SignatureAlgorithm.HMAC_SHA256, "ACTIVE", null, 4);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(credentialId), eq(3L))).thenReturn(List.of(current));
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(credentialId))).thenReturn(List.of(winner));

        assertThatThrownBy(() -> new JdbcOrganizationManagementRepository(jdbc).updateCredential(credentialId, 3,
                "atk-loser", "k8s://prod/agentteams#loser", "ACTIVE", null, NOW))
                .isInstanceOf(OptimisticLockFailure.class)
                .hasMessageContaining("expected version 3 but was 4");
    }
}
