package io.agentteams.controlplane.artifact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.persistence.ArtifactRepository;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.FoundationTransaction;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.domain.task.TaskPhase;
import io.agentteams.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class InternalArtifactUploadControllerTest {
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID ATTEMPT_ID = UUID.randomUUID();
    private static final String CONTENT = "plan body";

    @Mock private FoundationPersistenceService persistence;
    @Mock private FoundationTransaction transaction;
    @Mock private ArtifactRepository artifactRepository;
    @Mock private ObjectStorage storage;
    private MockMvc mockMvc;
    private String contentSha256;

    @BeforeEach
    void setUp() throws Exception {
        contentSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(CONTENT.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ArtifactService artifacts = new ArtifactService(storage);
        ArtifactCompletionService completion = new ArtifactCompletionService(persistence, artifacts,
                Clock.systemUTC());
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new InternalArtifactUploadController(artifacts, completion, persistence, "secret"))
                .build();
    }

    @Test
    void rejectsRequestsWithoutInternalToken() throws Exception {
        mockMvc.perform(post("/internal/v1/artifacts/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prepareBody("worker-1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void preparesPresignedUploadForTheOwningAgent() throws Exception {
        when(persistence.findTaskAttempt(ATTEMPT_ID)).thenReturn(Optional.of(attempt("worker-1")));
        when(storage.presignPut(anyString(), anyString(), any()))
                .thenReturn(URI.create("http://minio/put").toURL());
        when(storage.presignGet(anyString(), any())).thenReturn(URI.create("http://minio/get").toURL());

        mockMvc.perform(post("/internal/v1/artifacts/uploads")
                        .header(InternalArtifactUploadController.TOKEN_HEADER, "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prepareBody("worker-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storageKey").value("tasks/" + TASK_ID + "/attempts/" + ATTEMPT_ID
                        + "/artifacts/plan.md"))
                .andExpect(jsonPath("$.uploadUrl").value("http://minio/put"))
                .andExpect(jsonPath("$.downloadUrl").value("http://minio/get"));
    }

    @Test
    void rejectsPrepareForUnknownAttempts() throws Exception {
        when(persistence.findTaskAttempt(ATTEMPT_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post("/internal/v1/artifacts/uploads")
                        .header(InternalArtifactUploadController.TOKEN_HEADER, "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prepareBody("worker-1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsPrepareForForeignAgents() throws Exception {
        when(persistence.findTaskAttempt(ATTEMPT_ID)).thenReturn(Optional.of(attempt("someone-else")));

        mockMvc.perform(post("/internal/v1/artifacts/uploads")
                        .header(InternalArtifactUploadController.TOKEN_HEADER, "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prepareBody("worker-1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsCompletionWhenStorageKeyDoesNotMatchTheAttempt() throws Exception {
        when(persistence.findTaskAttempt(ATTEMPT_ID)).thenReturn(Optional.of(attempt("worker-1")));

        mockMvc.perform(post("/internal/v1/artifacts/complete")
                        .header(InternalArtifactUploadController.TOKEN_HEADER, "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("worker-1", "tasks/other/attempts/other/artifacts/plan.md")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsCompletionWhenStoredBytesDoNotMatchTheDigest() {
        when(persistence.findTaskAttempt(ATTEMPT_ID)).thenReturn(Optional.of(attempt("worker-1")));
        when(storage.download(anyString()))
                .thenReturn(new ByteArrayInputStream("tampered".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // The verification failure surfaces as a ServletException in standalone MockMvc; the
        // cause must remain the checksum mismatch so the Gateway maps it to INTERNAL.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mockMvc.perform(
                        post("/internal/v1/artifacts/complete")
                                .header(InternalArtifactUploadController.TOKEN_HEADER, "secret")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(completeBody("worker-1", expectedKey()))))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("artifact checksum or size mismatch");
    }

    @Test
    void completesVerifiedUploadAndExposesArtifactMetadata() throws Exception {
        when(persistence.findTaskAttempt(ATTEMPT_ID)).thenReturn(Optional.of(attempt("worker-1")));
        when(storage.download(anyString()))
                .thenReturn(new ByteArrayInputStream(CONTENT.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(persistence.inTransaction(any())).thenAnswer(invocation -> {
            java.util.function.Function<FoundationTransaction, Object> work = invocation.getArgument(0);
            return work.apply(transaction);
        });
        when(transaction.artifacts()).thenReturn(artifactRepository);
        when(artifactRepository.insertIfAbsent(any())).thenReturn(true);

        mockMvc.perform(post("/internal/v1/artifacts/complete")
                        .header(InternalArtifactUploadController.TOKEN_HEADER, "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody("worker-1", expectedKey())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storageKey").value(expectedKey()))
                .andExpect(jsonPath("$.artifactId").isNotEmpty());
    }

    private String expectedKey() {
        return "tasks/" + TASK_ID + "/attempts/" + ATTEMPT_ID + "/artifacts/plan.md";
    }

    private String prepareBody(String agentId) {
        return "{\"taskId\":\"" + TASK_ID + "\",\"attemptId\":\"" + ATTEMPT_ID + "\",\"agentId\":\"" + agentId
                + "\",\"name\":\"plan.md\",\"contentType\":\"text/markdown\",\"expirySeconds\":900}";
    }

    private String completeBody(String agentId, String storageKey) {
        return "{\"taskId\":\"" + TASK_ID + "\",\"attemptId\":\"" + ATTEMPT_ID + "\",\"agentId\":\"" + agentId
                + "\",\"name\":\"plan.md\",\"storageKey\":\"" + storageKey
                + "\",\"contentType\":\"text/markdown\",\"sizeBytes\":" + CONTENT.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8).length
                + ",\"sha256\":\"" + contentSha256 + "\"}";
    }

    private static TaskAttemptRecord attempt(String actor) {
        return new TaskAttemptRecord(ATTEMPT_ID, TASK_ID, UUID.randomUUID(), TaskPhase.RUNNING,
                Instant.EPOCH.plusSeconds(3600), null, actor, "leader", "", "",
                Instant.EPOCH, Instant.EPOCH, 0);
    }
}
