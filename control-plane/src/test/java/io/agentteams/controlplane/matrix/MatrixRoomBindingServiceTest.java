package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatrixRoomBindingServiceTest {
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEAM_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-09-28T04:00:00Z");

    @Test
    void acceptsEachPrimaryContextAndKeepsItsIdentity() {
        InMemoryRepository repository = new InMemoryRepository();
        MatrixRoomBindingService service = new MatrixRoomBindingService(repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.bind("org-1", "tenant-1", "!project:example.org", MatrixRoomContextType.PROJECT,
                PROJECT_ID, PROJECT_ID, TEAM_ID, "alice").primaryContextType())
                .isEqualTo(MatrixRoomContextType.PROJECT);
        assertThat(service.bind("org-1", "tenant-1", "!team:example.org", MatrixRoomContextType.TEAM,
                TEAM_ID, PROJECT_ID, TEAM_ID, "alice").primaryContextId()).isEqualTo(TEAM_ID);
        assertThat(service.bind("org-1", "tenant-1", "!conversation:example.org",
                MatrixRoomContextType.CONVERSATION, CONVERSATION_ID, PROJECT_ID, TEAM_ID, "alice")
                .primaryContextId()).isEqualTo(CONVERSATION_ID);
        assertThat(service.bind("org-1", "tenant-1", "!task:example.org", MatrixRoomContextType.TASK,
                TASK_ID, PROJECT_ID, TEAM_ID, "alice").primaryContextId()).isEqualTo(TASK_ID);
    }

    @Test
    void rejectsInvalidContextAndDuplicateActiveBindingsButKeepsRevokedHistory() {
        InMemoryRepository repository = new InMemoryRepository();
        MatrixRoomBindingService service = new MatrixRoomBindingService(repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.bind("org-1", "tenant-1", "!room:example.org",
                MatrixRoomContextType.CONVERSATION, CONVERSATION_ID, PROJECT_ID, null, "alice"))
                .isInstanceOf(IllegalArgumentException.class);

        MatrixRoomBinding first = service.bind("org-1", "tenant-1", "!room:example.org",
                MatrixRoomContextType.CONVERSATION, CONVERSATION_ID, PROJECT_ID, TEAM_ID, "alice");
        assertThatThrownBy(() -> service.bind("org-1", "tenant-1", "!room:example.org",
                MatrixRoomContextType.TASK, TASK_ID, PROJECT_ID, TEAM_ID, "alice"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.bind("org-1", "tenant-1", "!other:example.org",
                MatrixRoomContextType.CONVERSATION, CONVERSATION_ID, PROJECT_ID, TEAM_ID, "alice"))
                .isInstanceOf(IllegalStateException.class);

        repository.replace(first.withStatus(MatrixRoomBindingStatus.REVOKED));
        MatrixRoomBinding replacement = service.bind("org-1", "tenant-1", "!room:example.org",
                MatrixRoomContextType.CONVERSATION, CONVERSATION_ID, PROJECT_ID, TEAM_ID, "alice");
        assertThat(replacement.version()).isZero();
        assertThat(repository.all()).extracting(MatrixRoomBinding::status)
                .containsExactly(MatrixRoomBindingStatus.REVOKED, MatrixRoomBindingStatus.ACTIVE);
    }

    @Test
    void allowsSameContextInDifferentTenantsButRejectsMissingRequiredFields() {
        InMemoryRepository repository = new InMemoryRepository();
        MatrixRoomBindingService service = new MatrixRoomBindingService(repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        MatrixRoomBinding tenantOne = service.bind("org-1", "tenant-1", "!room:example.org",
                MatrixRoomContextType.PROJECT, PROJECT_ID, PROJECT_ID, TEAM_ID, "alice");
        MatrixRoomBinding tenantTwo = service.bind("org-1", "tenant-2", "!room:example.org",
                MatrixRoomContextType.PROJECT, PROJECT_ID, PROJECT_ID, TEAM_ID, "bob");
        assertThat(tenantOne.tenantId()).isNotEqualTo(tenantTwo.tenantId());

        assertThatThrownBy(() -> service.bind("org-1", "tenant-1", " ", MatrixRoomContextType.PROJECT,
                PROJECT_ID, PROJECT_ID, TEAM_ID, "alice")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.bind("org-1", "tenant-1", "!room:example.org",
                MatrixRoomContextType.PROJECT, null, PROJECT_ID, TEAM_ID, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class InMemoryRepository implements MatrixRoomBindingRepository {
        private final List<MatrixRoomBinding> values = new ArrayList<>();

        @Override
        public Optional<MatrixRoomBinding> findActiveByRoom(String tenantId, String roomId) {
            return values.stream().filter(item -> item.tenantId().equals(tenantId)
                    && item.roomId().equals(roomId) && item.status() == MatrixRoomBindingStatus.ACTIVE).findFirst();
        }

        @Override
        public Optional<MatrixRoomBinding> findActiveByContext(String tenantId, MatrixRoomContextType type,
                UUID contextId) {
            return values.stream().filter(item -> item.tenantId().equals(tenantId)
                    && item.primaryContextType() == type && item.primaryContextId().equals(contextId)
                    && item.status() == MatrixRoomBindingStatus.ACTIVE).findFirst();
        }

        @Override
        public MatrixRoomBinding save(MatrixRoomBinding binding) {
            values.add(binding);
            return binding;
        }

        void replace(MatrixRoomBinding binding) {
            values.replaceAll(item -> item.id().equals(binding.id()) ? binding : item);
        }

        List<MatrixRoomBinding> all() {
            return List.copyOf(values);
        }
    }
}
