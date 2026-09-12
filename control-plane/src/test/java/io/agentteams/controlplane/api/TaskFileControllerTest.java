package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.controlplane.taskfile.TaskFileRecord;
import io.agentteams.controlplane.taskfile.TaskFileService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

class TaskFileControllerTest {
    private static final UUID TASK = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-12T00:00:00Z");

    private final TaskFileService files = mock(TaskFileService.class);
    private final TaskService tasks = mock(TaskService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new TaskFileController(files, tasks))
                .setControllerAdvice(new ApiErrorHandler()).build();
        // standalone 测试无安全链：PrincipalContext 无 principal，
        // requireExistingTaskScope 只走 tasks.get（未 stub 返回 null，不影响断言）。
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void uploadReturnsCreatedLocation() throws Exception {
        UUID fileId = UUID.randomUUID();
        when(files.upload(eq(TASK), eq("report.pdf"), eq("application/pdf"), any(byte[].class)))
                .thenReturn(new TaskFileRecord(fileId, TASK, null, TaskFileRecord.OUTPUT, "report.pdf",
                        "application/pdf", 5, "ab".repeat(32), "tasks/" + TASK + "/files/" + fileId
                        + "/report.pdf", null, null, TaskFileRecord.AVAILABLE, NOW, NOW));

        mvc.perform(multipart("/api/v1/tasks/{taskId}/files", TASK)
                        .file(new MockMultipartFile("file", "report.pdf", "application/pdf",
                                "hello".getBytes())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileId").value(fileId.toString()))
                .andExpect(jsonPath("$.sha256").value("ab".repeat(32)))
                .andExpect(header().string("Location", "/api/v1/tasks/" + TASK + "/files/" + fileId));
    }

    @Test
    void registerInputRequiresIdempotencyKeyAndValidatesEntries() throws Exception {
        mvc.perform(post("/api/v1/tasks/{taskId}/attachments", TASK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachments\":[{\"sessionId\":\"" + UUID.randomUUID()
                                + "\",\"fileId\":\"" + UUID.randomUUID()
                                + "\",\"name\":\"a.pdf\",\"sizeBytes\":3}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerInputReturnsRecords() throws Exception {
        UUID fileId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID sourceFileId = UUID.randomUUID();
        when(files.registerInput(TASK, sessionId, sourceFileId, "输入.pdf", 3))
                .thenReturn(new TaskFileRecord(fileId, TASK, null, TaskFileRecord.INPUT, "输入.pdf",
                        null, 3, null, "", sessionId, sourceFileId, TaskFileRecord.AVAILABLE, NOW, NOW));

        mvc.perform(post("/api/v1/tasks/{taskId}/attachments", TASK)
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachments\":[{\"sessionId\":\"" + sessionId
                                + "\",\"fileId\":\"" + sourceFileId
                                + "\",\"name\":\"输入.pdf\",\"sizeBytes\":3}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.files[0].fileId").value(fileId.toString()))
                .andExpect(jsonPath("$.files[0].role").value("INPUT"));
    }

    @Test
    void contentStreamsAndInputIs409() throws Exception {
        UUID outputId = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID inputId = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        when(files.get(TASK, outputId)).thenReturn(new TaskFileRecord(outputId, TASK, null,
                TaskFileRecord.OUTPUT, "a.pdf", "application/pdf", 3, "aa",
                "tasks/" + TASK + "/files/" + outputId + "/a.pdf", null, null,
                TaskFileRecord.AVAILABLE, NOW, NOW));
        when(files.get(TASK, inputId)).thenReturn(new TaskFileRecord(inputId, TASK, null,
                TaskFileRecord.INPUT, "b.pdf", null, 3, null, "",
                UUID.randomUUID(), UUID.randomUUID(), TaskFileRecord.AVAILABLE, NOW, NOW));
        when(files.outputContent(any(TaskFileRecord.class)))
                .thenReturn(new java.io.ByteArrayInputStream("pdf".getBytes()));

        mvc.perform(get("/api/v1/tasks/{taskId}/files/{fileId}/content", TASK, outputId))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tasks/{taskId}/files/{fileId}/content", TASK, inputId))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownTaskIs404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(files.list(unknown, null)).thenThrow(new ResourceNotFoundException("task", unknown));
        mvc.perform(get("/api/v1/tasks/{taskId}/files", unknown))
                .andExpect(status().isNotFound());
    }

    @Test
    void storageDisabledIs503() throws Exception {
        when(files.upload(any(), anyString(), anyString(), any(byte[].class)))
                .thenThrow(new IllegalStateException("object storage is not enabled"));
        mvc.perform(multipart("/api/v1/tasks/{taskId}/files", TASK)
                        .file(new MockMultipartFile("file", "a.txt", MediaType.TEXT_PLAIN_VALUE,
                                "hi".getBytes())))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void attachmentsRegistrationFailsWithoutAttachmentsBody() throws Exception {
        mvc.perform(post("/api/v1/tasks/{taskId}/attachments", TASK)
                        .header("Idempotency-Key", "k-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void attachmentsRegistrationRejectsMissingRequiredFields() throws Exception {
        mvc.perform(post("/api/v1/tasks/{taskId}/attachments", TASK)
                        .header("Idempotency-Key", "k-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attachments\":[{\"sessionId\":\"" + UUID.randomUUID()
                                + "\",\"name\":\"a.pdf\",\"sizeBytes\":3}]}"))
                .andExpect(status().isBadRequest());
    }
}
