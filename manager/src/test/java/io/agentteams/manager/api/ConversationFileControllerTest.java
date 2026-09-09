package io.agentteams.manager.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.manager.conversation.ConversationFile;
import io.agentteams.manager.conversation.ConversationFileService;
import io.agentteams.manager.conversation.ConversationRuntimePort;
import io.agentteams.manager.conversation.ConversationService;
import io.agentteams.manager.security.ConversationScopeAuthorizer;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import java.net.URL;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConversationFileControllerTest {
    private static final UUID SESSION = UUID.randomUUID();
    private final ConversationFileService files = mock(ConversationFileService.class);
    private final ConversationService conversations = mock(ConversationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new ConversationFileController(files, conversations,
                                ConversationScopeAuthorizer.legacy()))
                .build();
        ManagerRequestContext.set(new ManagerPrincipal("user-1", "tenant-a", "project-a", "team-a", Set.of()));
        when(conversations.get(SESSION)).thenReturn(new ConversationService.Conversation(
                SESSION, new ConversationRuntimePort.Context("project-a", "team-a", "qwenpaw", null, SESSION),
                ConversationService.Status.ACTIVE));
    }

    @AfterEach
    void tearDown() {
        ManagerRequestContext.clear();
    }

    @Test
    void uploadReturnsFileLocation() throws Exception {
        UUID fileId = UUID.randomUUID();
        when(files.upload(any(), anyString(), anyString(), any(byte[].class)))
                .thenReturn(new ConversationFile(fileId, SESSION, "report.pdf", "application/pdf",
                        5, "conversations/" + SESSION + "/files/" + fileId + "/report.pdf", Instant.EPOCH));
        mvc.perform(multipart("/api/v1/conversations/{sessionId}/files", SESSION)
                        .file(new MockMultipartFile("file", "report.pdf", MediaType.APPLICATION_PDF_VALUE,
                                "hello".getBytes())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileId").value(fileId.toString()))
                .andExpect(jsonPath("$.name").value("report.pdf"))
                .andExpect(header().string("Location",
                        "/api/v1/conversations/" + SESSION + "/files/" + fileId));
    }

    @Test
    void downloadRedirectsToPresignedUrl() throws Exception {
        UUID fileId = UUID.randomUUID();
        when(files.presignDownload(any(), any(), any()))
                .thenReturn(new URL("https://minio.test/signed"));
        mvc.perform(get("/api/v1/conversations/{sessionId}/files/{fileId}", SESSION, fileId))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://minio.test/signed"));
    }

    @Test
    void downloadReturns404WhenMissing() throws Exception {
        when(files.presignDownload(any(), any(), any())).thenReturn(null);
        mvc.perform(get("/api/v1/conversations/{sessionId}/files/{fileId}", SESSION, UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadReturns503WhenStorageDisabled() throws Exception {
        when(files.upload(any(), anyString(), anyString(), any(byte[].class)))
                .thenThrow(new IllegalStateException("object storage is not enabled"));
        mvc.perform(multipart("/api/v1/conversations/{sessionId}/files", SESSION)
                        .file(new MockMultipartFile("file", "a.txt", MediaType.TEXT_PLAIN_VALUE, "hi".getBytes())))
                .andExpect(status().isServiceUnavailable());
    }
}
