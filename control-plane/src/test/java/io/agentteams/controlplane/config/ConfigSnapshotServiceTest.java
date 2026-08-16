package io.agentteams.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigSnapshotServiceTest {
    @Test
    void normalizesManifestComputesChecksumAndCreatesImmutableVersion() {
        ConfigSnapshotRepository repository = mock(ConfigSnapshotRepository.class);
        when(repository.findByChecksum(eq("worker"), any())).thenReturn(Optional.empty());
        when(repository.nextVersion("worker")).thenReturn(7L);
        when(repository.insertIfAbsent(any())).thenReturn(true);
        ConfigSnapshot snapshot = new ConfigSnapshot(UUID.randomUUID(), "worker", 7, "{}", "unused", "x",
                Instant.EPOCH);
        ConfigSnapshotService service = new ConfigSnapshotService(repository,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        ConfigSnapshot created = service.create("worker", "{ \"enabled\": true }", "manager");

        assertThat(created.version()).isEqualTo(7);
        assertThat(created.manifestJson()).isEqualTo("{\"enabled\":true}");
        assertThat(created.checksum()).hasSize(64);
        verify(repository).insertIfAbsent(any(ConfigSnapshot.class));
    }

    @Test
    void rejectsNonObjectManifest() {
        ConfigSnapshotRepository repository = mock(ConfigSnapshotRepository.class);
        ConfigSnapshotService service = new ConfigSnapshotService(repository, Clock.systemUTC());

        assertThatThrownBy(() -> service.create("worker", "[]", "manager"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("manifest");
    }
}
