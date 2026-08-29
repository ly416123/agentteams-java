package io.agentteams.manager.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.manager.ManagerSessionService;
import io.agentteams.manager.ManagerToolRegistry;
import io.agentteams.manager.ModelProvider;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ManagerSessionServiceFacadeTest {
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

    @org.junit.jupiter.api.AfterEach
    void clearRequestContext() {
        ManagerRequestContext.clear();
    }

    @Test
    void rejectsAccessToSessionOutsideAuthenticatedScope() {
        InMemoryManagerSessionRepository repository = new InMemoryManagerSessionRepository();
        ManagerSessionServiceFacade facade = new ManagerSessionServiceFacade(repository, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ManagerSessionRecord session = facade.createSession(
                new ManagerSessionServiceFacade.CreateSessionCommand("tenant-a", "project-a", "actor-a"),
                "session-key");
        ManagerRequestContext.set(new ManagerPrincipal("actor-b", "tenant-b", "project-b", "team-b",
                Set.of("task:create")));

        assertThatThrownBy(() -> facade.getSession(session.id()))
                .isInstanceOf(io.agentteams.manager.security.ManagerAuthorizationException.class);
        assertThatThrownBy(() -> facade.events(session.id(), 0))
                .isInstanceOf(io.agentteams.manager.security.ManagerAuthorizationException.class);
    }

    @Test
    void usesVerifiedPrincipalPermissionsWhenAppendingManagerMessage() {
        ModelProvider provider = request -> new ModelProvider.ModelResponse(
                "{\"intent\":\"CREATE_TASK\",\"title\":\"Task\",\"description\":\"body\"}",
                "test-model", 0, 0);
        ManagerSessionService model = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> "task-created"))));
        InMemoryManagerSessionRepository repository = new InMemoryManagerSessionRepository();
        ManagerSessionServiceFacade facade = new ManagerSessionServiceFacade(repository, model,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ManagerSessionRecord session = facade.createSession(
                new ManagerSessionServiceFacade.CreateSessionCommand("tenant-a", "project-a", "actor-a"),
                "session-key");
        ManagerRequestContext.set(new ManagerPrincipal("actor-a", "tenant-a", "project-a", "team-a",
                Set.of("task:create", "task:read")));

        facade.appendMessage(session.id(), 0, "message-key", "actor-a", "create",
                Set.of(), false, null, null);

        assertThat(repository.toolCalls()).hasSize(1);
    }

    @Test
    void createsSessionAndReplaysDuplicateMessageWithoutCallingModelTwice() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelProvider provider = request -> {
            modelCalls.incrementAndGet();
            return new ModelProvider.ModelResponse(
                    "{\"intent\":\"CREATE_TASK\",\"title\":\"Persisted\","
                            + "\"description\":\"message\",\"required_capabilities\":[],"
                            + "\"priority\":10,\"requires_approval\":false}",
                    "test-model", 1, 1);
        };
        ManagerSessionService model = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> "task-created"))));
        InMemoryManagerSessionRepository repository = new InMemoryManagerSessionRepository();
        ManagerSessionServiceFacade facade = new ManagerSessionServiceFacade(repository, model,
                Clock.fixed(NOW, ZoneOffset.UTC));

        ManagerSessionRecord created = facade.createSession(
                new ManagerSessionServiceFacade.CreateSessionCommand("tenant-a", "project-a", "actor-a"),
                "session-key");
        ManagerSessionServiceFacade.MessageResult first = facade.appendMessage(created.id(), 0, "message-key",
                "actor-a", "create a task", Set.of("task:create"), false, null, null);
        ManagerSessionServiceFacade.MessageResult duplicate = facade.appendMessage(created.id(), 0, "message-key",
                "actor-a", "create a task", Set.of("task:create"), false, null, null);

        assertThat(first).isEqualTo(duplicate);
        assertThat(first.session().version()).isEqualTo(1);
        assertThat(repository.eventsAfter(created.id(), 0)).extracting(ManagerEventRecord::cursor)
                .containsExactly(1L, 2L);
        assertThat(modelCalls).hasValue(1);
    }

    @Test
    void rejectsStaleVersionBeforeCallingModel() {
        ModelProvider provider = request -> new ModelProvider.ModelResponse(
                "{\"intent\":\"CREATE_TASK\",\"title\":\"Task\",\"description\":\"body\"}",
                "test-model", 0, 0);
        ManagerSessionService model = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> "task-created"))));
        InMemoryManagerSessionRepository repository = new InMemoryManagerSessionRepository();
        ManagerSessionServiceFacade facade = new ManagerSessionServiceFacade(repository, model,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ManagerSessionRecord session = facade.createSession(
                new ManagerSessionServiceFacade.CreateSessionCommand("tenant-a", "project-a", "actor-a"),
                "session-key");
        facade.appendMessage(session.id(), 0, "message-key", "actor-a", "first",
                Set.of("task:create"), false, null, null);

        assertThatThrownBy(() -> facade.appendMessage(session.id(), 0, "another-key", "actor-a", "second",
                Set.of("task:create"), false, null, null))
                .isInstanceOf(SessionVersionConflictException.class);
    }

    @Test
    void rejectsReuseOfMessageIdempotencyKeyWithDifferentContent() {
        ModelProvider provider = request -> new ModelProvider.ModelResponse(
                "{\"intent\":\"CREATE_TASK\",\"title\":\"Task\",\"description\":\"body\"}",
                "test-model", 0, 0);
        ManagerSessionService model = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> "task-created"))));
        InMemoryManagerSessionRepository repository = new InMemoryManagerSessionRepository();
        ManagerSessionServiceFacade facade = new ManagerSessionServiceFacade(repository, model,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ManagerSessionRecord session = facade.createSession(
                new ManagerSessionServiceFacade.CreateSessionCommand("tenant-a", "project-a", "actor-a"),
                "session-key");
        facade.appendMessage(session.id(), 0, "message-key", "actor-a", "first",
                Set.of("task:create"), false, null, null);

        assertThatThrownBy(() -> facade.appendMessage(session.id(), 1, "message-key", "actor-a", "different",
                Set.of("task:create"), false, null, null))
                .isInstanceOf(io.agentteams.manager.ManagerToolConflictException.class);
    }

    @Test
    void concurrentSameKeyClaimsOnceAndDoesNotCallModelTwice() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        ModelProvider provider = request -> {
            modelCalls.incrementAndGet();
            providerEntered.countDown();
            try {
                releaseProvider.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return new ModelProvider.ModelResponse(
                    "{\"intent\":\"CREATE_TASK\",\"title\":\"Persisted\","
                            + "\"description\":\"message\",\"required_capabilities\":[],"
                            + "\"priority\":10,\"requires_approval\":false}", "test-model", 1, 1);
        };
        ManagerSessionService model = new ManagerSessionService(provider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> "task-created"))));
        InMemoryManagerSessionRepository repository = new InMemoryManagerSessionRepository();
        ManagerSessionServiceFacade facade = new ManagerSessionServiceFacade(repository, model,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ManagerSessionRecord session = facade.createSession(
                new ManagerSessionServiceFacade.CreateSessionCommand("tenant-a", "project-a", "actor-a"),
                "session-key");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> facade.appendMessage(session.id(), 0, "same-key", "actor-a",
                    "create", Set.of("task:create"), false, null, null));
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var duplicate = executor.submit(() -> facade.appendMessage(session.id(), 0, "same-key", "actor-a",
                    "create", Set.of("task:create"), false, null, null));
            ManagerSessionServiceFacade.MessageResult processing = duplicate.get(5, TimeUnit.SECONDS);
            assertThat(processing.message().status()).isEqualTo(ManagerMessageRecord.Status.PROCESSING);
            releaseProvider.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).message().status())
                    .isEqualTo(ManagerMessageRecord.Status.COMPLETED);
            assertThat(modelCalls).hasValue(1);
        } finally {
            releaseProvider.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void persistsCancellationAndPreventsLaterMessages() {
        InMemoryManagerSessionRepository repository = new InMemoryManagerSessionRepository();
        ManagerSessionServiceFacade facade = new ManagerSessionServiceFacade(repository, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ManagerSessionRecord session = facade.createSession(
                new ManagerSessionServiceFacade.CreateSessionCommand("tenant-a", "project-a", "actor-a"),
                "session-key");

        ManagerSessionRecord cancelled = facade.cancel(session.id(), 0, "cancel-key", "actor-a");

        assertThat(cancelled.status()).isEqualTo(ManagerSessionRecord.Status.CANCELLED);
        assertThatThrownBy(() -> facade.appendMessage(session.id(), 1, "message-key", "actor-a", "late",
                Set.of("task:create"), false, null, null))
                .isInstanceOf(SessionCancelledException.class);
    }

    @Test
    void invalidModelOutputAndPermissionRejectionAreNotPersistedAsSuccessfulToolCalls() {
        ModelProvider invalidProvider = request -> new ModelProvider.ModelResponse("not-json", "test-model", 0, 0);
        ManagerSessionService invalidModel = new ManagerSessionService(invalidProvider, new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> "must-not-run"))));
        InMemoryManagerSessionRepository repository = new InMemoryManagerSessionRepository();
        ManagerSessionServiceFacade facade = new ManagerSessionServiceFacade(repository, invalidModel,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ManagerSessionRecord session = facade.createSession(
                new ManagerSessionServiceFacade.CreateSessionCommand("tenant-a", "project-a", "actor-a"),
                "session-key");

        assertThatThrownBy(() -> facade.appendMessage(session.id(), 0, "message-key", "actor-a", "bad",
                Set.of("task:create"), false, null, null))
                .isInstanceOf(io.agentteams.manager.InvalidModelOutputException.class);
        assertThat(repository.toolCalls()).isEmpty();

        ManagerSessionService permittedModel = new ManagerSessionService(
                request -> new ModelProvider.ModelResponse(
                        "{\"intent\":\"CREATE_TASK\",\"title\":\"Task\",\"description\":\"body\"}",
                        "test-model", 0, 0), new ObjectMapper(),
                new ManagerToolRegistry(Map.of("create_task", new ManagerToolRegistry.Tool(
                        "task:create", false, input -> "must-not-run"))));
        ManagerSessionServiceFacade permissionFacade = new ManagerSessionServiceFacade(repository, permittedModel,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ManagerSessionRecord second = permissionFacade.createSession(
                new ManagerSessionServiceFacade.CreateSessionCommand("tenant-a", "project-a", "actor-a"),
                "session-key-2");

        assertThatThrownBy(() -> permissionFacade.appendMessage(second.id(), 0, "message-key-2", "actor-a", "bad",
                Set.of(), false, null, null)).isInstanceOf(SecurityException.class);
        assertThat(repository.toolCalls()).isEmpty();
    }

    @Test
    void readsEventsAfterCursorFromASecondFacadeInstance() {
        InMemoryManagerSessionRepository repository = new InMemoryManagerSessionRepository();
        ManagerSessionServiceFacade facade = new ManagerSessionServiceFacade(repository, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        ManagerSessionRecord session = facade.createSession(
                new ManagerSessionServiceFacade.CreateSessionCommand("tenant-a", "project-a", "actor-a"),
                "session-key");
        facade.cancel(session.id(), 0, "cancel-key", "actor-a");

        ManagerSessionServiceFacade restarted = new ManagerSessionServiceFacade(repository, null,
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));

        assertThat(restarted.events(session.id(), 1)).extracting(ManagerEventRecord::cursor).containsExactly(2L);
    }

    private static final class InMemoryManagerSessionRepository implements ManagerSessionRepository {
        private final Map<UUID, ManagerSessionRecord> sessions = new java.util.LinkedHashMap<>();
        private final Map<String, ManagerSessionRecord> sessionKeys = new java.util.HashMap<>();
        private final Map<String, ManagerMessageRecord> messages = new java.util.HashMap<>();
        private final Map<String, ManagerToolCallRecord> toolCalls = new java.util.HashMap<>();
        private final Map<UUID, List<ManagerEventRecord>> events = new java.util.HashMap<>();
        private final Map<String, ManagerEventRecord> eventKeys = new java.util.HashMap<>();

        @Override
        public ManagerSessionRecord insertSession(ManagerSessionRecord session, String idempotencyKey) {
            ManagerSessionRecord prior = sessionKeys.putIfAbsent(idempotencyKey, session);
            if (prior != null) return prior;
            sessions.put(session.id(), session);
            events.put(session.id(), new java.util.ArrayList<>());
            return session;
        }

        @Override public java.util.Optional<ManagerSessionRecord> findSession(UUID id) {
            return java.util.Optional.ofNullable(sessions.get(id));
        }

        @Override public List<ManagerSessionRecord> findSessions(String tenantId, String projectId, String actor,
                Instant beforeUpdatedAt, UUID beforeId, int limit) {
            return sessions.values().stream()
                    .filter(session -> session.tenantId().equals(tenantId)
                            && session.projectId().equals(projectId) && session.actor().equals(actor))
                    .filter(session -> beforeUpdatedAt == null
                            || session.updatedAt().isBefore(beforeUpdatedAt)
                            || (session.updatedAt().equals(beforeUpdatedAt) && session.id().compareTo(beforeId) < 0))
                    .sorted(java.util.Comparator.comparing(ManagerSessionRecord::updatedAt).reversed()
                            .thenComparing(ManagerSessionRecord::id, java.util.Comparator.reverseOrder()))
                    .limit(limit)
                    .toList();
        }

        @Override public java.util.Optional<ManagerMessageRecord> findMessage(UUID sessionId, String key) {
            return java.util.Optional.ofNullable(messages.get(sessionId + ":" + key));
        }

        @Override
        public synchronized MessageReservation reserveMessage(UUID sessionId, long expectedVersion,
                ManagerMessageRecord message) {
            ManagerMessageRecord prior = messages.get(sessionId + ":" + message.idempotencyKey());
            ManagerSessionRecord current = sessions.get(sessionId);
            if (prior != null) return new MessageReservation(current, prior, false);
            if (current.status() == ManagerSessionRecord.Status.CANCELLED) throw new SessionCancelledException();
            if (current.version() != expectedVersion) throw new SessionVersionConflictException(sessionId,
                    expectedVersion, current.version());
            messages.put(sessionId + ":" + message.idempotencyKey(), message);
            ManagerSessionRecord updated = current.withStatus(ManagerSessionRecord.Status.ACTIVE, message.createdAt());
            sessions.put(sessionId, updated);
            return new MessageReservation(updated, message, true);
        }

        @Override public synchronized ManagerMessageRecord completeMessage(UUID sessionId, String key,
                String resultSummary) {
            ManagerMessageRecord updated = messages.get(sessionId + ":" + key).completed(resultSummary);
            messages.put(sessionId + ":" + key, updated);
            return updated;
        }

        @Override public synchronized ManagerMessageRecord failMessage(UUID sessionId, String key,
                String resultSummary) {
            ManagerMessageRecord updated = messages.get(sessionId + ":" + key).failed(resultSummary);
            messages.put(sessionId + ":" + key, updated);
            return updated;
        }

        @Override public void insertMessage(ManagerMessageRecord message) {
            messages.put(message.sessionId() + ":" + message.idempotencyKey(), message);
        }

        @Override public java.util.Optional<ManagerToolCallRecord> findToolCall(UUID sessionId, String key) {
            return java.util.Optional.ofNullable(toolCalls.get(sessionId + ":" + key));
        }

        @Override public void insertToolCall(ManagerToolCallRecord toolCall) {
            toolCalls.put(toolCall.sessionId() + ":" + toolCall.idempotencyKey(), toolCall);
        }

        @Override public ManagerSessionRecord updateSession(UUID id, long expectedVersion,
                ManagerSessionRecord.Status status, Instant updatedAt) {
            ManagerSessionRecord current = sessions.get(id);
            if (current.version() != expectedVersion) throw new SessionVersionConflictException(id,
                    expectedVersion, current.version());
            ManagerSessionRecord updated = current.withStatus(status, updatedAt);
            sessions.put(id, updated);
            return updated;
        }

        @Override public ManagerEventRecord appendEvent(UUID sessionId, String type, String payload,
                String idempotencyKey, Instant createdAt) {
            List<ManagerEventRecord> stream = events.get(sessionId);
            ManagerEventRecord event = new ManagerEventRecord(sessionId, stream.size() + 1L, type, payload, createdAt);
            stream.add(event);
            if (idempotencyKey != null) eventKeys.put(sessionId + ":" + idempotencyKey, event);
            return event;
        }

        @Override public java.util.Optional<ManagerEventRecord> findEvent(UUID sessionId, String idempotencyKey) {
            return java.util.Optional.ofNullable(eventKeys.get(sessionId + ":" + idempotencyKey));
        }

        @Override public List<ManagerEventRecord> findEventsAfter(UUID sessionId, long cursor) {
            return events.getOrDefault(sessionId, List.of()).stream()
                    .filter(event -> event.cursor() > cursor).toList();
        }

        List<ManagerEventRecord> eventsAfter(UUID sessionId, long cursor) { return findEventsAfter(sessionId, cursor); }
        List<ManagerToolCallRecord> toolCalls() { return List.copyOf(toolCalls.values()); }
    }
}
