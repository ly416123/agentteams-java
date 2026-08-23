package io.agentteams.controlplane.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.artifact.ArtifactCompletionService;
import io.agentteams.controlplane.artifact.ArtifactService;
import io.agentteams.controlplane.artifact.ArtifactUpload;
import io.agentteams.controlplane.persistence.ArtifactRecord;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.domain.task.TaskPhase;
import java.net.URL;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ArtifactControllerTest {
    @Mock
    private ArtifactService artifacts;
    @Mock
    private ArtifactCompletionService completion;
    @Mock
    private FoundationPersistenceService persistence;
    @Mock
    private TaskService tasks;
    @Mock
    private ResourceScopeRepository resourceScopes;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new ArtifactController(artifacts, completion, persistence, tasks, resourceScopes))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @Test
    void preparesDirectUploadWithCanonicalStoragePath() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        when(persistence.findTaskAttempt(attemptId)).thenReturn(java.util.Optional.of(attempt(taskId, attemptId)));
        String key = "tasks/" + taskId + "/attempts/" + attemptId + "/artifacts/result.json";
        ArtifactUpload upload = new ArtifactUpload(taskId, attemptId, "result.json", key,
                new URL("https://minio.test/upload"), new URL("https://minio.test/download"));
        when(artifacts.prepareUpload(eq(taskId), eq(attemptId), eq("result.json"), eq("application/json"), any()))
                .thenReturn(upload);

        mockMvc.perform(post("/api/v1/tasks/{taskId}/attempts/{attemptId}/artifacts/uploads", taskId, attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"result.json\",\"contentType\":\"application/json\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storageKey").value(key))
                .andExpect(jsonPath("$.uploadUrl").value("https://minio.test/upload"));
    }

    @Test
    void verifiesAndCompletesOnlyTheCanonicalArtifactPath() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        when(persistence.findTaskAttempt(attemptId)).thenReturn(java.util.Optional.of(attempt(taskId, attemptId)));
        String key = "tasks/" + taskId + "/attempts/" + attemptId + "/artifacts/result.json";
        ArtifactRecord record = new ArtifactRecord(UUID.randomUUID(), taskId, attemptId, "result.json", key,
                "application/json", 7, "abc", "AVAILABLE", "{}", Instant.now(), Instant.now(), 0);
        when(completion.complete(any())).thenReturn(record);

        mockMvc.perform(post("/api/v1/tasks/{taskId}/attempts/{attemptId}/artifacts/complete", taskId, attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"result.json\",\"storageKey\":\"" + key
                                + "\",\"contentType\":\"application/json\",\"sizeBytes\":7,"
                                + "\"sha256\":\"abc\",\"metadata\":{\"kind\":\"result\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        ArgumentCaptor<ArtifactCompletionService.CompletionRequest> request = ArgumentCaptor.forClass(
                ArtifactCompletionService.CompletionRequest.class);
        verify(completion).complete(request.capture());
        assertThat(request.getValue().storageKey()).isEqualTo(key);
        assertThat(request.getValue().metadataJson()).contains("kind");
    }

    @Test
    void rejectsAnArtifactPathFromAnotherTask() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        when(persistence.findTaskAttempt(attemptId)).thenReturn(java.util.Optional.of(attempt(taskId, attemptId)));
        mockMvc.perform(post("/api/v1/tasks/{taskId}/attempts/{attemptId}/artifacts/complete", taskId, attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"result.json\",\"storageKey\":\"tasks/other/artifacts/result.json\","
                                + "\"contentType\":\"application/json\",\"sizeBytes\":1,\"sha256\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void listsArtifactsWithShortLivedDownloadUrls() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        when(persistence.findTaskAttempt(attemptId)).thenReturn(java.util.Optional.of(attempt(taskId, attemptId)));
        UUID artifactId = UUID.randomUUID();
        String key = "tasks/" + taskId + "/attempts/" + attemptId + "/artifacts/result.json";
        ArtifactRecord record = new ArtifactRecord(artifactId, taskId, attemptId, "result.json", key,
                "application/json", 7, "abc", "AVAILABLE", "{}", Instant.now(), Instant.now(), 0);
        when(persistence.findArtifactsByTaskIdAndAttemptId(taskId, attemptId)).thenReturn(java.util.List.of(record));
        when(artifacts.prepareDownload(eq(key), any())).thenReturn(new URL("https://minio.test/download"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/tasks/{taskId}/attempts/{attemptId}/artifacts", taskId, attemptId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(artifactId.toString()))
                .andExpect(jsonPath("$[0].downloadUrl").value("https://minio.test/download"));

        when(persistence.findArtifact(artifactId)).thenReturn(java.util.Optional.of(record));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/tasks/{taskId}/attempts/{attemptId}/artifacts/{artifactId}", taskId, attemptId,
                        artifactId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("result.json"));
    }

    @Test
    void rejectsAnArtifactReadFromAnotherProject() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        String key = "tasks/" + taskId + "/attempts/" + attemptId + "/artifacts/result.json";
        ArtifactRecord record = new ArtifactRecord(artifactId, taskId, attemptId, "result.json", key,
                "application/json", 7, "abc", "AVAILABLE", "{}", Instant.now(), Instant.now(), 0);
        when(persistence.findTaskAttempt(attemptId)).thenReturn(java.util.Optional.of(attempt(taskId, attemptId)));
        when(persistence.findArtifact(artifactId)).thenReturn(java.util.Optional.of(record));
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                java.util.Set.of("artifact:read")));
        doThrow(new AuthorizationException("resource is outside caller project"))
                .when(resourceScopes).requireVisible("ARTIFACT", artifactId);
        try {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                            "/api/v1/tasks/{taskId}/attempts/{attemptId}/artifacts/{artifactId}", taskId, attemptId,
                            artifactId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        } finally {
            PrincipalContext.clear();
        }
    }

    private static TaskAttemptRecord attempt(UUID taskId, UUID attemptId) {
        Instant now = Instant.now();
        return new TaskAttemptRecord(attemptId, taskId, UUID.randomUUID(), TaskPhase.RUNNING,
                now.plusSeconds(60), null, "worker", "grpc", null, null, now, now, 0);
    }
}
