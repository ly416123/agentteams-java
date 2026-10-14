package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.AgentLeaseRecord;
import io.agentteams.controlplane.persistence.AgentLeaseRepository;
import io.agentteams.controlplane.persistence.DomainEventRepository;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.FoundationTransaction;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.persistence.TaskAttemptRepository;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.persistence.TaskRepository;
import io.agentteams.domain.task.LeaseNotActiveException;
import io.agentteams.domain.task.LeaseNotActiveReason;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.domain.task.TaskTransitionCommand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecutionEventServiceLateEventTest {

    private static final Instant START = Instant.parse("2026-10-05T13:27:46Z");
    private final UUID taskId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final UUID leaseId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();

    private FoundationPersistenceService persistence;
    private TaskRecord task;
    private TaskAttemptRecord attempt;
    private AgentLeaseRecord lease;
    private ExecutionEventService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        persistence = mock(FoundationPersistenceService.class);
        FoundationTransaction tx = mock(FoundationTransaction.class);
        TaskRepository tasks = mock(TaskRepository.class);
        TaskAttemptRepository attempts = mock(TaskAttemptRepository.class);
        AgentLeaseRepository leases = mock(AgentLeaseRepository.class);
        DomainEventRepository events = mock(DomainEventRepository.class);

        task = new TaskRecord(taskId, "terminal-task", "late event", TaskPhase.FAILED, 0,
                "{}", "agent", "gateway", "RUNTIME_FAILURE", "failed",
                START, START.plusSeconds(18), 5);
        attempt = new TaskAttemptRecord(attemptId, taskId, leaseId, TaskPhase.FAILED,
                START.plusSeconds(600), START.plusSeconds(18), "agent", "gateway",
                "RUNTIME_FAILURE", "failed", START, START.plusSeconds(18), 5);
        lease = new AgentLeaseRecord(leaseId, agentId, attemptId, START, START.plusSeconds(600),
                START.plusSeconds(18), "RELEASED", START, START.plusSeconds(18), 2);

        when(tx.tasks()).thenReturn(tasks);
        when(tx.taskAttempts()).thenReturn(attempts);
        when(tx.agentLeases()).thenReturn(leases);
        when(tx.domainEvents()).thenReturn(events);
        when(tasks.findById(taskId)).thenReturn(Optional.of(task));
        when(attempts.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(leases.findById(leaseId)).thenReturn(Optional.of(lease));
        when(events.findByEventId(any())).thenReturn(Optional.empty());
        when(persistence.inTransaction(any())).thenAnswer(invocation ->
                ((Function<FoundationTransaction, Object>) invocation.getArgument(0)).apply(tx));
        service = new ExecutionEventService(persistence);
    }

    @Test
    void treatsAheadOfVersionHeartbeatAsInactiveAfterTaskCompletion() {
        assertThatThrownBy(() -> service.renewLease(taskId,
                new io.agentteams.domain.task.LeaseRenewalCommand(UUID.randomUUID(), task.version() + 1,
                        attemptId, leaseId, START.plusSeconds(13), START.plusSeconds(660),
                        agentId.toString(), "gateway")))
                .isInstanceOf(LeaseNotActiveException.class)
                .extracting(error -> ((LeaseNotActiveException) error).reason())
                .isEqualTo(LeaseNotActiveReason.COMPLETED);
    }

    @Test
    void treatsAheadOfVersionExecutionEventAsImpossibleAfterTaskCompletion() {
        assertThatThrownBy(() -> service.apply(taskId, TaskTransitionCommand.forAttempt(
                UUID.randomUUID(), task.version() + 1, TaskPhase.FAILED, attemptId, leaseId,
                START.plusSeconds(19), agentId.toString(), "gateway"), List.of()))
                .isInstanceOf(io.agentteams.domain.task.IllegalTaskTransitionException.class);
    }
}
