package io.agentteams.controlplane.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class TaskSourceEvidenceTest {

    @Test
    void captureRejectsMissingTaskScopeAndOversizedSourceSetsBeforePersistence() throws Exception {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskSourceEvidenceRepository repository = mock(TaskSourceEvidenceRepository.class);
        TaskSourceEvidenceService service = new TaskSourceEvidenceService(runs, repository, java.time.Clock.systemUTC());
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();
        assertThatThrownBy(() -> service.capture(taskId, attemptId, leaseId, "call-1", "search", "idem",
                List.of(new TaskSourceEvidenceService.IncomingSource("title", "https://example.test", "a".repeat(64)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("task scope");
        assertThatThrownBy(() -> service.capture(taskId, attemptId, leaseId, "call-1", "search", "idem",
                java.util.Collections.nCopies(21, new TaskSourceEvidenceService.IncomingSource("title",
                        "https://example.test", "a".repeat(64)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source count");
        io.agentteams.controlplane.security.ExecutionContext context = new io.agentteams.controlplane.security.ExecutionContext(
                "org", "tenant", "project", "team", "agent-worker");
        when(runs.contextForTask(taskId)).thenReturn(java.util.Optional.of(context));
        when(repository.activeRun(context, taskId)).thenReturn(UUID.randomUUID());
        when(repository.isActiveExecution(eq(taskId), eq(attemptId), eq(leaseId), any())).thenReturn(false);
        assertThatThrownBy(() -> service.capture(taskId, attemptId, leaseId, "call-1", "search", "idem",
                List.of(new TaskSourceEvidenceService.IncomingSource("title", "https://example.test", "a".repeat(64)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease");
        String url = "https://example.test";
        String snippet = "Official documentation excerpt.";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(url.getBytes(StandardCharsets.UTF_8)));
        when(repository.isActiveExecution(eq(taskId), eq(attemptId), eq(leaseId), any())).thenReturn(true);
        TaskSourceEvidenceService.Receipt receipt = service.capture(taskId, attemptId, leaseId, "call-1", "search", "idem",
                List.of(new TaskSourceEvidenceService.IncomingSource("title", url, hash, snippet)));
        assertThat(receipt.sourceCount()).isEqualTo(1);
        verify(repository).save(eq(context), eq(taskId), any(), eq(attemptId), eq("call-1"), eq("search"), eq("idem"),
                argThat(sources -> sources.size() == 1 && sources.get(0).snippet().equals(snippet)));
    }

    @Test
    void keepsOnlyBoundedHttpSourcesAndStableReferences() throws Exception {
        String url = "https://www.w3.org/TR/WCAG20-TECHS/pdf.html";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(url.getBytes(StandardCharsets.UTF_8)));
        UUID taskId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        TaskSourceEvidence.Source source = new TaskSourceEvidence.Source(
                "W3C PDF guidance", url, hash, "task-source:call-1:" + hash);

        TaskSourceEvidence evidence = new TaskSourceEvidence(taskId, runId,
                List.of(new TaskSourceEvidence.SearchCall(taskId, runId, UUID.randomUUID(), "call-1", "bailian_web_search",
                        true, List.of(source), java.time.Instant.EPOCH)), false, 1, 1);

        assertThat(evidence.taskId()).isEqualTo(taskId);
        assertThat(evidence.calls()).singleElement().satisfies(call -> {
            assertThat(call.sources()).containsExactly(source);
            assertThat(call.complete()).isTrue();
        });
    }

    @Test
    void deduplicatesSourcesByUrlBeforePersistence() throws Exception {
        TaskRunObservationRepository runs = mock(TaskRunObservationRepository.class);
        TaskSourceEvidenceRepository repository = mock(TaskSourceEvidenceRepository.class);
        TaskSourceEvidenceService service = new TaskSourceEvidenceService(runs, repository, java.time.Clock.systemUTC());
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();
        io.agentteams.controlplane.security.ExecutionContext context = new io.agentteams.controlplane.security.ExecutionContext(
                "org", "tenant", "project", "team", "agent-worker");
        when(runs.contextForTask(taskId)).thenReturn(java.util.Optional.of(context));
        when(repository.activeRun(context, taskId)).thenReturn(UUID.randomUUID());
        when(repository.isActiveExecution(eq(taskId), eq(attemptId), eq(leaseId), any())).thenReturn(true);
        String url = "https://example.test";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(url.getBytes(StandardCharsets.UTF_8)));

        TaskSourceEvidenceService.Receipt receipt = service.capture(taskId, attemptId, leaseId, "call-1", "search", "idem",
                List.of(new TaskSourceEvidenceService.IncomingSource("first", url, hash),
                        new TaskSourceEvidenceService.IncomingSource("second", url, hash)));

        assertThat(receipt.sourceCount()).isEqualTo(1);
        verify(repository).save(eq(context), eq(taskId), any(), eq(attemptId), eq("call-1"), eq("search"), eq("idem"),
                argThat(sources -> sources.size() == 1 && sources.get(0).title().equals("first")));
    }

    @Test
    void rejectsUntrustedUrlsAndMismatchedSourceHashes() throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("https://example.test".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> new TaskSourceEvidence.Source("bad", "https://user:pass@example.test", hash,
                "task-source:call-1:" + hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskSourceEvidence.Source("bad", "https://example.test#fragment", hash,
                "task-source:call-1:" + hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskSourceEvidence.Source("bad", "file:///etc/passwd", hash,
                "task-source:call-1:" + hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskSourceEvidence.Source("bad", "https://example.test", "0".repeat(64),
                "task-source:call-1:" + hash)).isInstanceOf(IllegalArgumentException.class);
    }
}
