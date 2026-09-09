# 会话文件交付（回复内嵌下载链接）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 会话中 AI 生成的文件可通过 `upload_file` MCP 工具上传 MinIO，用户点击 AI 回复中的链接下载。

**架构：** 扩展既有 `agentteams-task-mcp.py` stdio server 新增 `upload_file` 工具（qwenpaw pod 内读本地文件 → multipart POST 到 Manager）→ Manager 服务端直传 MinIO 并落库 `conversation_files` → 返回永久短链（`GET /api/v1/conversations/{sid}/files/{fileId}` 302 到 15 分钟 presigned GET）。Manager 在发给 qwenpaw 的每条消息前注入平台上下文块（sessionId + 工具使用指令），best-effort 引导 AI 调用。

**技术栈：** Spring Boot 3 / Java 17（manager 模块）、Python 3 stdlib（MCP 脚本）、MinIO（`agentteams-storage` 库）、Flyway（PostgreSQL）。

**规格：** `docs/superpowers/specs/2026-09-09-conversation-file-delivery-design.md`

**对规格的偏离（有意决策）：** 规格第 4.1 节写「新建 `scripts/agentteams-file-mcp.py`」；本计划改为**扩展既有 `scripts/agentteams-task-mcp.py`**（新增 `upload_file` 工具）。理由：QwenPaw 的 MCP client 注册/激活链有已知多层坑（inactive 状态需 rollout restart、client.env 不转发等，见一期 pitfall），并入已注册且已激活的 stdio server 只需一次 CM 更新 + rollout restart，部署风险大幅降低；两个工具共享 token 缓存与配置合并逻辑（DRY）。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `manager/src/main/resources/db/manager-migration/V10__conversation_files.sql` | 创建 | 会话文件表 |
| `manager/src/main/java/io/agentteams/manager/conversation/ConversationFile.java` | 创建 | 文件记录值对象（record） |
| `manager/src/main/java/io/agentteams/manager/conversation/JdbcConversationFileRepository.java` | 创建 | 落库/查询 |
| `manager/src/main/java/io/agentteams/manager/conversation/ConversationFileService.java` | 创建 | 上传/下载业务（清洗、限额、存储、presign） |
| `manager/src/main/java/io/agentteams/manager/api/ConversationFileController.java` | 创建 | POST 上传（鉴权）/ GET 下载（匿名 302） |
| `manager/pom.xml` | 修改 | 加 `agentteams-storage` 依赖 |
| `manager/src/main/resources/application.yml` | 修改 | multipart 上限 |
| `manager/src/main/java/io/agentteams/manager/conversation/QwenPawConversationRuntime.java` | 修改 | `requestBody()` 注入平台上下文块 |
| `scripts/agentteams-task-mcp.py` | 修改 | 新增 `upload_file` 工具 + multipart helper + 3 个新配置键 |
| `scripts/test_agentteams_task_mcp.py` | 修改 | 新工具合同测试 |
| `deploy/kind-qwenpaw-task-mcp.yaml` | 修改 | CM 内嵌脚本同步 + mcp-env.json 加 3 键 |
| `deploy/helm/agentteams-java/templates/manager.yaml` | 修改 | manager 加 storage env 块 |
| `scripts/run-l5-conversation-file-delivery.py` | 创建 | L5 端到端验收脚本 |

测试文件与源文件同包（`manager/src/test/java/...`、`scripts/test_agentteams_task_mcp.py`）。

既有模式参考（实现者必读）：
- `scripts/agentteams-task-mcp.py` 全文（工具注册、`_http_json`、`_fetch_token`、`handle_request` 兜底、软失败启动）
- `manager/src/main/java/io/agentteams/manager/api/ConversationController.java`（`ManagerRequestContext.require()` 认证、`ConversationScopeAuthorizer` scope 校验、record 响应风格）
- `manager/src/main/java/io/agentteams/manager/security/ManagerSecurityConfiguration.java`（chain 已 `anyRequest().permitAll()`，认证由 `ManagerAuthenticationFilter` 填充 `ManagerRequestContext`——**无需改 security 配置**）
- `deploy/helm/agentteams-java/templates/control-plane.yaml:179-194`（storage env 块模板，manager.yaml 照抄）
- `storage/src/main/java/io/agentteams/storage/StorageAutoConfiguration.java`（`agentteams.storage.enabled=true` 激活 `ObjectStorage` bean）

---

### 任务 1：`conversation_files` 表与 Repository

**文件：**
- 创建：`manager/src/main/resources/db/manager-migration/V10__conversation_files.sql`
- 创建：`manager/src/main/java/io/agentteams/manager/conversation/ConversationFile.java`
- 创建：`manager/src/main/java/io/agentteams/manager/conversation/JdbcConversationFileRepository.java`
- 测试：`manager/src/test/java/io/agentteams/manager/conversation/JdbcConversationFileRepositoryTest.java`

- [ ] **步骤 1：编写失败的测试**

```java
package io.agentteams.manager.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcConversationFileRepositoryTest {
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID FILE = UUID.randomUUID();

    @Test
    void insertBindsAllColumns() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcConversationFileRepository repository = new JdbcConversationFileRepository(jdbc);
        ConversationFile file = new ConversationFile(FILE, SESSION, "report.pdf", "application/pdf",
                36881L, "conversations/" + SESSION + "/files/" + FILE + "/report.pdf", Instant.EPOCH);
        repository.insert(file);
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void findQueriesBySessionAndFileId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new ConversationFile(FILE, SESSION, "report.pdf", "application/pdf",
                        36881L, "conversations/" + SESSION + "/files/" + FILE + "/report.pdf", Instant.EPOCH)));
        JdbcConversationFileRepository repository = new JdbcConversationFileRepository(jdbc);
        ConversationFile found = repository.find(SESSION, FILE);
        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo(FILE);
        assertThat(found.sessionId()).isEqualTo(SESSION);
    }

    @SuppressWarnings("unchecked")
    @Test
    void findReturnsNullWhenMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        assertThat(new JdbcConversationFileRepository(jdbc).find(SESSION, FILE)).isNull();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -pl manager test -Dtest=JdbcConversationFileRepositoryTest`
预期：编译失败（类不存在）。

- [ ] **步骤 3：编写迁移与实现**

`V10__conversation_files.sql`：

```sql
CREATE TABLE conversation_files (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES conversation_sessions(id),
    name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128),
    size_bytes BIGINT NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX conversation_files_session_idx ON conversation_files (session_id, created_at);
```

`ConversationFile.java`：

```java
package io.agentteams.manager.conversation;

import java.time.Instant;
import java.util.UUID;

/** A file produced by the conversation agent and persisted to object storage. */
public record ConversationFile(UUID id, UUID sessionId, String name, String contentType,
        long sizeBytes, String storageKey, Instant createdAt) { }
```

`JdbcConversationFileRepository.java`：

```java
package io.agentteams.manager.conversation;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Persistence for conversation-produced files. */
@Repository
public final class JdbcConversationFileRepository {
    private static final String INSERT = """
            INSERT INTO conversation_files (id, session_id, name, content_type, size_bytes,
                    storage_key, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_BY_ID = """
            SELECT id, session_id, name, content_type, size_bytes, storage_key, created_at
            FROM conversation_files WHERE session_id = ? AND id = ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcConversationFileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static RowMapper<ConversationFile> mapper() {
        return (rs, rowNum) -> new ConversationFile(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("session_id")),
                rs.getString("name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("storage_key"),
                rs.getTimestamp("created_at").toInstant());
    }

    public void insert(ConversationFile file) {
        jdbc.update(INSERT, file.id(), file.sessionId(), file.name(), file.contentType(),
                file.sizeBytes(), file.storageKey(), Timestamp.from(file.createdAt()));
    }

    public ConversationFile find(UUID sessionId, UUID fileId) {
        List<ConversationFile> rows = jdbc.query(SELECT_BY_ID, mapper(), sessionId, fileId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -pl manager test -Dtest=JdbcConversationFileRepositoryTest`
预期：PASS（3 个用例）。

- [ ] **步骤 5：Commit**

```bash
git add manager/src/main/resources/db/manager-migration/V10__conversation_files.sql \
        manager/src/main/java/io/agentteams/manager/conversation/ConversationFile.java \
        manager/src/main/java/io/agentteams/manager/conversation/JdbcConversationFileRepository.java \
        manager/src/test/java/io/agentteams/manager/conversation/JdbcConversationFileRepositoryTest.java
git commit -m "feat(manager): 新增会话文件表与仓储（V10）"
```

---

### 任务 2：`ConversationFileService`（清洗、限额、存储、presign）

**文件：**
- 创建：`manager/src/main/java/io/agentteams/manager/conversation/ConversationFileService.java`
- 测试：`manager/src/test/java/io/agentteams/manager/conversation/ConversationFileServiceTest.java`

- [ ] **步骤 1：编写失败的测试**

```java
package io.agentteams.manager.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.storage.ObjectStorage;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ConversationFileServiceTest {
    private static final UUID SESSION = UUID.randomUUID();
    private final ObjectStorage storage = mock(ObjectStorage.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ObjectStorage> storageProvider = mock(ObjectProvider.class);
    private final JdbcConversationFileRepository repository = mock(JdbcConversationFileRepository.class);
    private final ConversationFileService service = new ConversationFileService(storageProvider, repository);

    @BeforeEach
    void wireStorage() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
    }

    @Test
    void uploadSanitizesNameStoresObjectAndPersists() throws Exception {
        URL presigned = new URL("https://minio.test/get");
        when(storage.presignGet(anyString(), any(Duration.class))).thenReturn(presigned);
        ConversationFile file = service.upload(SESSION, "../../weird 空格 名.pdf\r\n",
                "application/pdf", new byte[] {1, 2, 3});
        assertThat(file.name()).isEqualTo("weird 空格 名.pdf");
        assertThat(file.sessionId()).isEqualTo(SESSION);
        assertThat(file.sizeBytes()).isEqualTo(3);
        assertThat(file.storageKey()).startsWith("conversations/" + SESSION + "/files/");
        assertThat(file.storageKey()).endsWith("/weird 空格 名.pdf");
        verify(storage).upload(anyString(), any(InputStream.class), anyLong(), anyString());
        verify(repository).insert(any(ConversationFile.class));
        assertThat(service.presignDownload(SESSION, file.id(), Duration.ofMinutes(15)))
                .isNotNull();
    }

    @Test
    void uploadRejectsOversizedContent() {
        byte[] big = new byte[(int) ConversationFileService.MAX_FILE_BYTES + 1];
        assertThatThrownBy(() -> service.upload(SESSION, "big.bin", "application/octet-stream", big))
                .isInstanceOf(ConversationFileService.FileTooLargeException.class);
        verify(storage, never()).upload(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    void sanitizedNameFallsBackToFileAndKeepsExtensionWhenTruncated() {
        String longName = "很长的名字".repeat(80) + ".pdf";
        String sanitized = ConversationFileService.sanitizeName(longName);
        assertThat(sanitized.length()).isLessThanOrEqualTo(255);
        assertThat(sanitized).endsWith(".pdf");
        assertThat(ConversationFileService.sanitizeName("///")).isEqualTo("file");
        assertThat(ConversationFileService.sanitizeName("a/b/c.txt")).isEqualTo("c.txt");
    }

    @Test
    void downloadPresignsWithRequestedExpiry() throws Exception {
        UUID fileId = UUID.randomUUID();
        when(repository.find(SESSION, fileId)).thenReturn(new ConversationFile(fileId, SESSION,
                "a.pdf", "application/pdf", 1, "conversations/" + SESSION + "/files/" + fileId + "/a.pdf",
                Instant.EPOCH));
        when(storage.presignGet(anyString(), any(Duration.class)))
                .thenReturn(new URL("https://minio.test/signed"));
        URL url = service.presignDownload(SESSION, fileId, Duration.ofMinutes(15));
        assertThat(url.toString()).isEqualTo("https://minio.test/signed");
    }

    @Test
    void downloadReturnsNullWhenFileMissing() {
        when(repository.find(SESSION, UUID.randomUUID())).thenReturn(null);
        assertThat(service.presignDownload(SESSION, UUID.randomUUID(), Duration.ofMinutes(15))).isNull();
    }

    @Test
    void requiresStorage() {
        when(storageProvider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.upload(SESSION, "a.txt", "text/plain", new byte[1]))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.presignDownload(SESSION, UUID.randomUUID(), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -pl manager test -Dtest=ConversationFileServiceTest`
预期：编译失败（类不存在）。

- [ ] **步骤 3：编写实现**

```java
package io.agentteams.manager.conversation;

import io.agentteams.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Uploads conversation-produced files to object storage and presigns downloads. */
@Service
public final class ConversationFileService {
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_NAME_LENGTH = 255;

    /** Raised when the uploaded file exceeds {@link #MAX_FILE_BYTES}. */
    public static final class FileTooLargeException extends RuntimeException {
        public FileTooLargeException(long size) {
            super("file exceeds " + MAX_FILE_BYTES + " bytes: " + size);
        }
    }

    private final ObjectProvider<ObjectStorage> storageProvider;
    private final JdbcConversationFileRepository repository;

    public ConversationFileService(ObjectProvider<ObjectStorage> storageProvider,
            JdbcConversationFileRepository repository) {
        this.storageProvider = storageProvider;
        this.repository = repository;
    }

    /** 清洗文件名：取最后路径段、去控制字符、截断（保留尾部扩展名）。 */
    static String sanitizeName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isBlank()) {
            return "file";
        }
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(name.length() - MAX_NAME_LENGTH);
    }

    private ObjectStorage requireStorage() {
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new IllegalStateException(
                    "object storage is not enabled (agentteams.storage.enabled=false)");
        }
        return storage;
    }

    /** Stores the bytes and persists the record; returns the stored file. */
    public ConversationFile upload(UUID sessionId, String originalName, String contentType, byte[] content) {
        ObjectStorage storage = requireStorage();
        if (content.length > MAX_FILE_BYTES) {
            throw new FileTooLargeException(content.length);
        }
        String name = sanitizeName(originalName);
        UUID fileId = UUID.randomUUID();
        String safeContentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        String storageKey = "conversations/" + sessionId + "/files/" + fileId + "/" + name;
        storage.upload(storageKey, new ByteArrayInputStream(content), content.length, safeContentType);
        ConversationFile file = new ConversationFile(fileId, sessionId, name, safeContentType,
                content.length, storageKey, Instant.now());
        repository.insert(file);
        return file;
    }

    /** Presigned GET for the record; null when the file does not exist. */
    public URL presignDownload(UUID sessionId, UUID fileId, Duration expiry) {
        ObjectStorage storage = requireStorage();
        ConversationFile file = repository.find(sessionId, fileId);
        if (file == null) {
            return null;
        }
        return storage.presignGet(file.storageKey(), expiry);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -pl manager test -Dtest=ConversationFileServiceTest`
预期：PASS（6 个用例）。

- [ ] **步骤 5：Commit**

```bash
git add manager/src/main/java/io/agentteams/manager/conversation/ConversationFileService.java \
        manager/src/test/java/io/agentteams/manager/conversation/ConversationFileServiceTest.java
git commit -m "feat(manager): 会话文件上传/预签名下载服务"
```

---

### 任务 3：`ConversationFileController` + 依赖与配置

**文件：**
- 创建：`manager/src/main/java/io/agentteams/manager/api/ConversationFileController.java`
- 修改：`manager/pom.xml`（`agentteams-storage` 依赖）
- 修改：`manager/src/main/resources/application.yml`（multipart 上限）
- 测试：`manager/src/test/java/io/agentteams/manager/api/ConversationFileControllerTest.java`

- [ ] **步骤 1：pom 与配置先落地（供后续编译）**

`manager/pom.xml` 在 `<dependencies>` 中（紧随 `agentteams-application-contracts` 依赖之后）加入：

```xml
        <dependency>
            <groupId>io.agentteams</groupId>
            <artifactId>agentteams-storage</artifactId>
        </dependency>
```

（版本由父 pom dependencyManagement 管理；若父 pom 未管理则带 `<version>${project.version}</version>`，与相邻依赖写法一致。）

`manager/src/main/resources/application.yml` 的 `spring:` 段内加入：

```yaml
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 55MB
```

- [ ] **步骤 2：编写失败的测试**

```java
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
import io.agentteams.manager.security.ManagerAuthenticationFilter;
import io.agentteams.manager.security.ManagerIdentityTokenValidator;
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
```

实现者注意：`ManagerPrincipal` / `ManagerRequestContext` 的真实构造器与方法名以现有代码为准
（参考 `ConversationControllerTest` 如何为受保护端点准备 principal；若该测试直接设置上下文，
照抄其写法；若其走 filter 链，则上传测试的鉴权断言拆到 filter 层已有覆盖，本测试只保留
业务断言并删除 `ManagerRequestContext.set/clear` 两行）。

- [ ] **步骤 3：运行测试验证失败**

运行：`mvn -pl manager test -Dtest=ConversationFileControllerTest`
预期：编译失败（controller 不存在）。

- [ ] **步骤 4：编写实现**

```java
package io.agentteams.manager.api;

import io.agentteams.manager.conversation.ConversationFile;
import io.agentteams.manager.conversation.ConversationFileService;
import io.agentteams.manager.conversation.ConversationService;
import io.agentteams.manager.conversation.ConversationRuntimeException;
import io.agentteams.manager.security.ConversationScopeAuthorizer;
import io.agentteams.manager.security.ManagerPrincipal;
import io.agentteams.manager.security.ManagerRequestContext;
import java.net.URL;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploads files produced by the conversation agent and serves presigned downloads.
 * POST requires an authenticated principal with conversation scope; GET is
 * intentionally anonymous (high-entropy fileId is the capability, mirroring the
 * anonymous presigned GET of the task artifact chain).
 */
@RestController
@RequestMapping("/api/v1/conversations")
public final class ConversationFileController {
    private final ConversationFileService files;
    private final ConversationService conversations;
    private final ConversationScopeAuthorizer scopeAuthorizer;

    public ConversationFileController(ConversationFileService files, ConversationService conversations,
            ConversationScopeAuthorizer scopeAuthorizer) {
        this.files = files;
        this.conversations = conversations;
        this.scopeAuthorizer = scopeAuthorizer;
    }

    @PostMapping("/{sessionId}/files")
    public ResponseEntity<FileResponse> upload(@PathVariable UUID sessionId,
            @RequestPart("file") MultipartFile part) throws Exception {
        ManagerPrincipal principal = ManagerRequestContext.require();
        // 服务身份（MCP 工具账号）代表用户上传：校验 scope 可达而非 owner 匹配。
        scopeAuthorizer.requireAccessible(
                conversations.get(sessionId).context().project(),
                conversations.get(sessionId).context().team(), principal);
        ConversationFile file = files.upload(sessionId, part.getOriginalFilename(),
                part.getContentType(), part.getBytes());
        String url = "/api/v1/conversations/" + sessionId + "/files/" + file.id();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", url)
                .body(new FileResponse(file.id(), file.name(), file.contentType(), file.sizeBytes(), url));
    }

    @GetMapping("/{sessionId}/files/{fileId}")
    public ResponseEntity<Void> download(@PathVariable UUID sessionId, @PathVariable UUID fileId) {
        URL presigned = files.presignDownload(sessionId, fileId, Duration.ofMinutes(15));
        if (presigned == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(presigned.toURI()).build();
    }

    /** Storage disabled (or misconfigured) degrades to an explicit 503, not a stack trace. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Void> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    /** Multipart over 50MB degrades to an explicit 413 (spec §6). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Void> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
    }

    public record FileResponse(UUID fileId, String name, String contentType, long sizeBytes, String url) { }
}
```

实现者注意：
- `conversations.get(sessionId)` 的无 owner 重载在 `ConversationService` 中已存在
  （`ConversationController.message` 的 `SessionResponse.from(service.get(sessionId))` 使用过）；
  若签名不符，以现有代码为准调整。
- `ConversationScopeAuthorizer.requireAccessible(project, team, principal)` 的准确签名以
  `ConversationController.requireScope` 现有调用为准。
- storage 未启用时 `ConversationFileService` 通过 `ObjectProvider<ObjectStorage>` 注入
  （任务 2 已实现），`agentteams.storage.enabled=false` 的部署照常启动，上传端点返回 503。

- [ ] **步骤 5：运行全部新测试验证通过**

运行：`mvn -pl manager test -Dtest='ConversationFile*Test'`
预期：PASS。

- [ ] **步骤 6：Commit**

```bash
git add manager/pom.xml manager/src/main/resources/application.yml \
        manager/src/main/java/io/agentteams/manager/api/ConversationFileController.java \
        manager/src/main/java/io/agentteams/manager/conversation/ConversationFileService.java \
        manager/src/main/java/io/agentteams/manager/conversation/JdbcConversationFileRepository.java \
        manager/src/test/java/io/agentteams/manager/
git commit -m "feat(manager): 会话文件上传与匿名预签名下载端点"
```

---

### 任务 4：会话链路平台上下文注入（sessionId + 工具指令）

**文件：**
- 修改：`manager/src/main/java/io/agentteams/manager/conversation/QwenPawConversationRuntime.java:590-609`（`requestBody`）
- 测试：`manager/src/test/java/io/agentteams/manager/conversation/QwenPawConversationRuntimeTest.java`（追加用例）

**背景：** 二期的 taskId 注入（`runtime` 模块 `QwenPawHttpRuntimePort.promptText()`）只作用于**任务执行链路**；会话链路（manager → qwenpaw `/api/console/chat`）当前无注入。本任务在会话链路补齐。

- [ ] **步骤 1：编写失败的测试**

在 `QwenPawConversationRuntimeTest` 中仿照现有「捕获请求体」用例（既有 server 捕获请求的写法），新增：

```java
    @Test
    void prefixesEveryMessageWithPlatformContextContainingSessionId() throws Exception {
        // 沿用该测试类现有的本地 HttpServer + captureRequest 模式：
        // server 返回一个最小 SSE（conversation.started + message.completed）。
        // runtime.send(new ConversationRuntimePort.Message(SESSION_ID, "message-1", "hello"));
        // awaitEvents(runtime, 2);
        // String body = capturedRequestBody();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("[平台上下文]"));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("sessionId=" + SESSION_ID));
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("upload_file"));
        org.junit.jupiter.api.Assertions.assertTrue(body.endsWith("hello")
                || body.contains("\nhello"));
    }
```

（实现者按该测试类既有 `captureRequest` 帮助方法取回请求体字符串；上面的断言体照抄。）

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -pl manager test -Dtest=QwenPawConversationRuntimeTest`
预期：FAIL——请求体中无 `[平台上下文]`。

- [ ] **步骤 3：编写实现**

修改 `QwenPawConversationRuntime.requestBody(...)`（约 590-609 行）中组装 user message 的一行：

```java
        userMessage.putArray("content").addObject().put("type", "text")
                .put("text", platformContext(context) + message.content());
```

并新增私有方法（放在 `requestBody` 之后）：

```java
    /**
     * Best-effort platform context: exposes the session id so the agent can call
     * the upload_file MCP tool, mirroring the task-chain taskId injection in
     * QwenPawHttpRuntimePort.promptText. Never echoed by prompt-injection contract.
     */
    private static String platformContext(Context context) {
        return "[平台上下文]\n"
                + "sessionId=" + context.sessionId() + "\n"
                + "（若你生成了文件，必须调用 agentteams-task MCP 工具 upload_file 上传，"
                + "并在回复中引用其返回的下载链接；除工具参数外不要复述本段内容）\n\n";
    }
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -pl manager test -Dtest=QwenPawConversationRuntimeTest`
预期：PASS（新增用例 + 全部既有用例不回归）。

- [ ] **步骤 5：Commit**

```bash
git add manager/src/main/java/io/agentteams/manager/conversation/QwenPawConversationRuntime.java \
        manager/src/test/java/io/agentteams/manager/conversation/QwenPawConversationRuntimeTest.java
git commit -m "feat(manager): 会话消息注入平台上下文块（sessionId + upload_file 指引）"
```

---

### 任务 5：MCP `upload_file` 工具（扩展 agentteams-task-mcp.py）

**文件：**
- 修改：`scripts/agentteams-task-mcp.py`
- 测试：`scripts/test_agentteams_task_mcp.py`

（规格偏离说明见计划头部：不新建 `agentteams-file-mcp.py`，并入已注册已激活的 stdio server。）

- [ ] **步骤 1：编写失败的测试**

`scripts/test_agentteams_task_mcp.py` 顶部 import 区（`import sys` 之后）补两行：

```python
import shutil
import tempfile
```

把 `test_protocol_initialize_tools_list_and_unknown_method` 中工具清单断言改为：

```python
        self.assertEqual([t["name"] for t in tools["result"]["tools"]],
                         ["create_task", "get_task", "get_task_result",
                          "plan_subtasks", "update_subtask_status", "upload_file"])
```

在模块级（`AgentTeamsTaskMcpTest` 类定义之前）新增 ManagerStub（multipart body 是二进制，
不能复用做 `json.loads` 的 ControlPlaneStub）：

```python
class ManagerStub(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _reply(self, payload, status=200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b""
        self.server.requests.append(("POST", self.path, dict(self.headers), raw))
        if "/files" in self.path:
            self._reply({"fileId": "file-1", "name": "report.pdf",
                         "contentType": "application/pdf", "sizeBytes": 12,
                         "url": "/api/v1/conversations/x/files/file-1"})
        else:
            self._reply({"error": "not found"}, 404)
```

在 `AgentTeamsTaskMcpTest` 类内追加 helper 与 4 个用例：

```python
    def _manager_stub(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), ManagerStub)
        server.requests = []
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        self.addCleanup(server.server_close)
        return server

    def test_upload_file_posts_multipart_to_manager(self):
        workspace = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, workspace, ignore_errors=True)
        with open(os.path.join(workspace, "report.pdf"), "wb") as handle:
            handle.write(b"%PDF-1.4 body")
        manager = self._manager_stub()
        manager_base = f"http://127.0.0.1:{manager.server_address[1]}"
        MCP._CONFIG = MCP.load_config({
            "AGENTTEAMS_CONTROL_PLANE_URL": self.base,
            "AGENTTEAMS_MCP_TOKEN": "static-test-token",
            "AGENTTEAMS_MANAGER_URL": manager_base,
            "AGENTTEAMS_CONSOLE_PUBLIC_URL": "http://console.test:30080",
            "AGENTTEAMS_WORKSPACE_DIR": workspace,
        })
        session = str(uuid.uuid4())
        response = rpc({"jsonrpc": "2.0", "id": 10, "method": "tools/call",
                        "params": {"name": "upload_file", "arguments": {
                            "session_id": session, "path": "report.pdf"}}})
        payload = json.loads(response["result"]["content"][0]["text"])
        self.assertTrue(payload["ok"], payload)
        self.assertEqual(payload["fileId"], "file-1")
        self.assertEqual(payload["url"],
                         "http://console.test:30080/api/v1/conversations/x/files/file-1")
        self.assertEqual(len(manager.requests), 1)
        _, path, headers, raw = manager.requests[0]
        self.assertEqual(path, f"/api/v1/conversations/{session}/files")
        self.assertEqual(headers.get("Authorization"), "Bearer static-test-token")
        self.assertIn("multipart/form-data; boundary=", headers.get("Content-Type", ""))
        self.assertIn(b'filename="report.pdf"', raw)
        self.assertIn(b"%PDF-1.4 body", raw)

    def test_upload_file_rejects_path_outside_workspace(self):
        workspace = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, workspace, ignore_errors=True)
        MCP._CONFIG = MCP.load_config({
            "AGENTTEAMS_CONTROL_PLANE_URL": self.base,
            "AGENTTEAMS_MCP_TOKEN": "static-test-token",
            "AGENTTEAMS_MANAGER_URL": "http://127.0.0.1:1",
            "AGENTTEAMS_WORKSPACE_DIR": workspace,
        })
        response = rpc({"jsonrpc": "2.0", "id": 11, "method": "tools/call",
                        "params": {"name": "upload_file", "arguments": {
                            "session_id": str(uuid.uuid4()),
                            "path": "../../etc/passwd"}}})
        self.assertTrue(response["result"]["isError"])
        self.assertIn("inside the agent workspace",
                      response["result"]["content"][0]["text"])

    def test_upload_file_rejects_oversized(self):
        workspace = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, workspace, ignore_errors=True)
        with open(os.path.join(workspace, "big.bin"), "wb") as handle:
            handle.write(b"x" * 9)
        MCP._CONFIG = MCP.load_config({
            "AGENTTEAMS_CONTROL_PLANE_URL": self.base,
            "AGENTTEAMS_MCP_TOKEN": "static-test-token",
            "AGENTTEAMS_MANAGER_URL": "http://127.0.0.1:1",
            "AGENTTEAMS_WORKSPACE_DIR": workspace,
        })
        original = MCP.MAX_UPLOAD_BYTES
        MCP.MAX_UPLOAD_BYTES = 8
        try:
            response = rpc({"jsonrpc": "2.0", "id": 12, "method": "tools/call",
                            "params": {"name": "upload_file", "arguments": {
                                "session_id": str(uuid.uuid4()), "path": "big.bin"}}})
        finally:
            MCP.MAX_UPLOAD_BYTES = original
        self.assertTrue(response["result"]["isError"])
        self.assertIn("exceeds", response["result"]["content"][0]["text"])

    def test_upload_file_requires_manager_url(self):
        MCP._CONFIG = MCP.load_config({
            "AGENTTEAMS_CONTROL_PLANE_URL": self.base,
            "AGENTTEAMS_MCP_TOKEN": "static-test-token",
        })
        response = rpc({"jsonrpc": "2.0", "id": 13, "method": "tools/call",
                        "params": {"name": "upload_file", "arguments": {
                            "session_id": str(uuid.uuid4()), "path": "a.txt"}}})
        self.assertTrue(response["result"]["isError"])
        self.assertIn("AGENTTEAMS_MANAGER_URL",
                      response["result"]["content"][0]["text"])
```

- [ ] **步骤 2：运行测试验证失败**

运行：`python3 scripts/test_agentteams_task_mcp.py -v`
预期：4 个 upload_file 用例 FAIL（"unsupported tool: upload_file"），工具清单断言 FAIL。

- [ ] **步骤 3：编写实现**

①`scripts/agentteams-task-mcp.py` 顶部 import（`import json` 之后）加一行：

```python
import mimetypes
```

②常量区（`MAX_SUBTASKS = 20` 之后）加：

```python
MAX_UPLOAD_BYTES = 50 * 1024 * 1024
```

③`TOOL_NAMES` 改为：

```python
TOOL_NAMES = ["create_task", "get_task", "get_task_result",
              "plan_subtasks", "update_subtask_status", "upload_file"]
```

④`TOOL_SCHEMAS` 字典内（`update_subtask_status` 条目之后）追加：

```python
    "upload_file": {
        "description": (
            "Upload a file generated inside the agent workspace (for example a "
            "PDF under output/) so the user can download it from the "
            "conversation. session_id comes from the [平台上下文] block. "
            "Returns a permanent download URL that you MUST reference in your "
            "reply as a markdown link."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "session_id": {"type": "string",
                               "description": "Current conversation session UUID."},
                "path": {"type": "string",
                         "description": "Workspace-relative path of the file."},
                "filename": {"type": "string",
                             "description": "Optional display name; defaults to basename."},
            },
            "required": ["session_id", "path"],
        },
    },
```

⑤`Config` dataclass 末尾加两个字段：

```python
    password: str | None
    manager_url: str | None
    console_public_url: str | None
    workspace_dir: str
```

⑥`load_config` 返回值在 `password=...` 之后加两行：

```python
        manager_url=(source.get("AGENTTEAMS_MANAGER_URL") or "").rstrip("/") or None,
        console_public_url=(source.get("AGENTTEAMS_CONSOLE_PUBLIC_URL") or "").rstrip("/") or None,
        workspace_dir=source.get("AGENTTEAMS_WORKSPACE_DIR") or "/app/working/workspaces/default",
```

⑦`_http_json` 之后新增两个 helper 与工具实现（放在 `tool_update_subtask_status` 之后、
`call_tool` 之前）：

```python
def _http_multipart(url: str, *, token: str, field: str, filename: str,
                    content: bytes, content_type: str) -> dict[str, Any]:
    boundary = "----agentteams" + uuid.uuid4().hex
    part = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("utf-8")
    body = part + content + f"\r\n--{boundary}--\r\n".encode()
    headers = {"Accept": "application/json",
               "Content-Type": f"multipart/form-data; boundary={boundary}"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")[:300]
        raise RuntimeError(f"HTTP {error.code} from {url}: {detail}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"connection to {url} failed: {error.reason}") from error
    if not payload:
        return {}
    return json.loads(payload)


def _resolve_workspace_path(config: Config, raw_path: str) -> str:
    """Realpath containment: symlink or .. traversal stays inside the workspace."""
    root = os.path.realpath(config.workspace_dir)
    candidate = raw_path if os.path.isabs(raw_path) else os.path.join(root, raw_path)
    resolved = os.path.realpath(candidate)
    if resolved != root and not resolved.startswith(root + os.sep):
        raise ValueError("path must be inside the agent workspace")
    return resolved


def tool_upload_file(arguments: dict[str, Any]) -> dict[str, Any]:
    config = get_config()
    if not config.manager_url:
        raise ValueError("AGENTTEAMS_MANAGER_URL is required for upload_file")
    if not config.console_public_url:
        raise ValueError("AGENTTEAMS_CONSOLE_PUBLIC_URL is required for upload_file")
    session_id = _safe_uuid(arguments.get("session_id"), "session_id")
    raw_path = arguments.get("path")
    if not isinstance(raw_path, str) or not raw_path.strip():
        raise ValueError("path must be a non-empty string")
    resolved = _resolve_workspace_path(config, raw_path.strip())
    if not os.path.isfile(resolved):
        raise ValueError(f"file not found in workspace: {raw_path}")
    if os.path.getsize(resolved) > MAX_UPLOAD_BYTES:
        raise ValueError(f"file exceeds the {MAX_UPLOAD_BYTES} byte upload limit")
    display = _clean_text(arguments.get("filename"), "filename", 255, required=False)
    if not display:
        display = os.path.basename(resolved) or "file"
    with open(resolved, "rb") as handle:
        content = handle.read()
    content_type = mimetypes.guess_type(resolved)[0] or "application/octet-stream"
    response = _http_multipart(
        config.manager_url + f"/api/v1/conversations/{session_id}/files",
        token=_fetch_token(), field="file",
        filename=display.replace('"', "'"),
        content=content, content_type=content_type)
    if not response.get("ok", True):
        return response
    absolute_url = config.console_public_url + response.get("url", "")
    return {"ok": True, "fileId": response.get("fileId"), "url": absolute_url,
            "name": response.get("name", display),
            "sizeBytes": response.get("sizeBytes"),
            "note": ("Uploaded. Include the url in your reply as a markdown link "
                     "so the user can download the file.")}
```

⑧`call_tool` 中 `update_subtask_status` 分支之后加：

```python
    if name == "upload_file":
        return tool_upload_file(arguments)
```

- [ ] **步骤 4：运行测试验证通过**

运行：`python3 scripts/test_agentteams_task_mcp.py -v`
预期：全部 PASS（含既有用例不回归）。

- [ ] **步骤 5：Commit**

```bash
git add scripts/agentteams-task-mcp.py scripts/test_agentteams_task_mcp.py
git commit -m "feat(mcp): 新增 upload_file 工具（工作区文件 multipart 上传到 Manager）"
```

---

### 任务 6：kind ConfigMap 同步（嵌入新脚本 + mcp-env.json 新增 3 键）

**文件：**
- 修改：`deploy/kind-qwenpaw-task-mcp.yaml`

守护测试 `test_deploy_configmap_embeds_current_script` 要求 CM 内嵌脚本与
`scripts/agentteams-task-mcp.py` 逐字节一致——用脚本重新生成而不是手抖手改。

- [ ] **步骤 1：重新生成 ConfigMap**

仓库根目录运行：

```bash
python3 - <<'PY'
import json
from pathlib import Path
import yaml

root = Path(".")
manifest_path = root / "deploy/kind-qwenpaw-task-mcp.yaml"
manifest = yaml.safe_load(manifest_path.read_text())
manifest["data"]["agentteams-task-mcp.py"] = \
    (root / "scripts/agentteams-task-mcp.py").read_text()
env = json.loads(manifest["data"]["mcp-env.json"])
env["AGENTTEAMS_MANAGER_URL"] = "http://agentteams-agentteams-java-manager:8080"
env["AGENTTEAMS_CONSOLE_PUBLIC_URL"] = "http://192.168.122.55:30080"
env["AGENTTEAMS_WORKSPACE_DIR"] = "/app/working/workspaces/default"
manifest["data"]["mcp-env.json"] = json.dumps(env, ensure_ascii=False, indent=2)

class LiteralDumper(yaml.SafeDumper):
    pass

LiteralDumper.add_representer(
    str,
    lambda dumper, value: dumper.represent_scalar(
        "tag:yaml.org,2002:str", value, style="|" if "\n" in value else None))
manifest_path.write_text(
    yaml.dump(manifest, Dumper=LiteralDumper, sort_keys=False, allow_unicode=True))
print("regenerated", manifest_path)
PY
```

（`AGENTTEAMS_MANAGER_URL` 是 manager Service 的集群内地址，kind/L5 同名；
`AGENTTEAMS_WORKSPACE_DIR` 与 L5 上 qwenpaw 实际工作区一致，已验证 PDF 落在
`/app/working/workspaces/default/output/`。）

- [ ] **步骤 2：验证守护测试通过**

运行：`python3 scripts/test_agentteams_task_mcp.py -v -k configmap`
预期：`test_deploy_configmap_embeds_current_script` PASS。
再全量跑一遍：`python3 scripts/test_agentteams_task_mcp.py`
预期：全部 PASS（stdio roundtrip 用例会真实起进程验证 CM 内嵌脚本可执行）。

- [ ] **步骤 3：Commit**

```bash
git add deploy/kind-qwenpaw-task-mcp.yaml
git commit -m "feat(deploy): 同步 qwenpaw task-mcp ConfigMap（upload_file + manager/console URL/workspace）"
```

---

### 任务 7：helm manager 注入 storage 环境块

**文件：**
- 修改：`deploy/helm/agentteams-java/templates/manager.yaml`

manager 需要与 control-plane 相同的 storage env 才能启用 `ObjectStorage` bean
（`StorageAutoConfiguration` 的 `@ConditionalOnProperty(name = "agentteams.storage.enabled",
havingValue = "true")` 需要 `AGENTTEAMS_STORAGE_ENABLED=true`，注意
control-plane.yaml 的 179-194 块不含 ENABLED 行，需额外补上）。

- [ ] **步骤 1：修改模板**

在 `manager.yaml` 的 env 列表末尾（`AGENTTEAMS_GATEWAY_PORT` 条目之后、`envFrom:` 之前）插入：

```yaml
{{- if .Values.storage.enabled }}
            - name: AGENTTEAMS_STORAGE_ENABLED
              value: "true"
            - name: AGENTTEAMS_STORAGE_ENDPOINT
              value: {{ .Values.storage.endpoint | quote }}
            - name: AGENTTEAMS_STORAGE_PRESIGN_ENDPOINT
              value: {{ .Values.storage.presignEndpoint | quote }}
            - name: AGENTTEAMS_STORAGE_REGION
              value: {{ .Values.storage.region | quote }}
            - name: AGENTTEAMS_STORAGE_BUCKET
              value: {{ .Values.storage.bucket | quote }}
            - name: AGENTTEAMS_STORAGE_ACCESS_KEY
              valueFrom:
                secretKeyRef: { name: {{ .Values.storage.existingSecret }}, key: access-key }
            - name: AGENTTEAMS_STORAGE_SECRET_KEY
              valueFrom:
                secretKeyRef: { name: {{ .Values.storage.existingSecret }}, key: secret-key }
{{- end }}
```

- [ ] **步骤 2：验证渲染**

运行：

```bash
helm template agentteams deploy/helm/agentteams-java --show-only templates/manager.yaml \
  | grep -c "AGENTTEAMS_STORAGE_BUCKET"
```

预期：输出 `1`（本机无 helm 时可跳过，部署阶段在 L5 上用 `helm template` 复验）。

- [ ] **步骤 3：Commit**

```bash
git add deploy/helm/agentteams-java/templates/manager.yaml
git commit -m "feat(deploy): manager 注入 storage 环境块（会话文件上传依赖）"
```

---

### 任务 8：L5 端到端验收脚本 + 全量回归

**文件：**
- 创建：`scripts/run-l5-conversation-file-delivery.py`

确定性层：alice token 创建会话 → multipart 上传 PDF → 匿名 302 → presigned 逐字节比对 →
404 负例。best-effort 层（默认开启，可 `--skip-best-effort`）：发消息请真模型生成 txt 并调
用 upload_file，10 分钟内轮询 history 出现下载链接则下载验证，未出现记 NOTE 不算失败
（与二期「真模型自主拆解 best-effort」同语义）。

- [ ] **步骤 1：编写脚本**

```python
#!/usr/bin/env python3
"""L5 验收：会话文件交付（确定性层 + 真模型 best-effort 层）。

确定性层：alice token 创建会话 → multipart 上传 PDF → 匿名 302 → presigned 比对 → 404 负例。
best-effort 层：发消息请 AI 生成 txt 并调用 upload_file，超时未交付记 NOTE。
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

FAILURES: list[str] = []
NOTES: list[str] = []

PDF_BYTES = (b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
             b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
             b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\n"
             b"trailer<</Size 4/Root 1 0 R>>\n%%EOF")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def fetch_token(keycloak_base: str, username: str, password: str) -> str:
    url = keycloak_base.rstrip("/") + "/realms/agentteams/protocol/openid-connect/token"
    form = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "agentteams-api",
        "username": username, "password": password}).encode()
    request = urllib.request.Request(url, data=form, method="POST")
    request.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.loads(response.read().decode())
    token = payload.get("access_token")
    if not token:
        raise SystemExit("token endpoint returned no access_token")
    return token


def http_request(url: str, method: str = "GET", *, token: str | None = None,
                 idempotency_key: str | None = None, json_body: dict | None = None,
                 data: bytes | None = None, content_type: str | None = None,
                 timeout: int = 60):
    """返回 (status, headers, body_bytes)；302 不跟随（Location 在 headers）。"""
    request = urllib.request.Request(url, method=method)
    if token:
        request.add_header("Authorization", "Bearer " + token)
    if idempotency_key:
        request.add_header("Idempotency-Key", idempotency_key)
    if json_body is not None:
        request.data = json.dumps(json_body).encode()  # 必须设 request.data，否则空 body
        request.add_header("Content-Type", "application/json")
    elif data is not None:
        request.data = data
        if content_type:
            request.add_header("Content-Type", content_type)
    try:
        with _OPENER.open(request, timeout=timeout) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read()


def multipart_body(field: str, filename: str, content: bytes,
                   content_type: str) -> tuple[bytes, str]:
    boundary = "----agentteams" + uuid.uuid4().hex
    part = (f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n").encode()
    return part + content + f"\r\n--{boundary}--\r\n".encode(), boundary


def check(name: str, ok: bool, detail: str = "") -> None:
    if ok:
        print(f"  PASS {name}")
    else:
        print(f"  FAIL {name} {detail}")
        FAILURES.append(name)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://192.168.122.55:30080")
    parser.add_argument("--keycloak-url", default="http://192.168.122.55:30082")
    parser.add_argument("--username", default="alice")
    parser.add_argument("--password", default="alice-dev")
    parser.add_argument("--skip-best-effort", action="store_true")
    parser.add_argument("--best-effort-timeout", type=int, default=600)
    args = parser.parse_args()
    manager = args.base_url.rstrip("/")
    token = fetch_token(args.keycloak_url, args.username, args.password)

    print("=== 确定性层 ===")
    session_id = str(uuid.uuid4())
    status, _, body = http_request(
        f"{manager}/api/v1/conversations", "POST", token=token,
        idempotency_key=f"l5-file-delivery-create-{session_id}",
        json_body={"sessionId": session_id, "projectId": "project-a",
                   "teamId": "team-a", "workerId": "qwenpaw"})
    check("create conversation 201", status == 201, f"got {status} {body[:200]}")

    upload_body, boundary = multipart_body("file", "l5-file-delivery.pdf",
                                           PDF_BYTES, "application/pdf")
    status, _, body = http_request(
        f"{manager}/api/v1/conversations/{session_id}/files", "POST", token=token,
        data=upload_body, content_type=f"multipart/form-data; boundary={boundary}")
    check("upload 201", status == 201, f"got {status} {body[:200]}")
    file_url = None
    try:
        payload = json.loads(body)
        file_url = payload.get("url")
        check("response has fileId/url", bool(payload.get("fileId")) and bool(file_url),
              str(payload)[:200])
    except Exception as error:
        check("response has fileId/url", False, str(error))

    if file_url:
        status, headers, _ = http_request(manager + file_url)
        check("anonymous download 302", status == 302, f"got {status}")
        location = headers.get("Location", "")
        check("Location is presigned absolute URL", location.startswith("http"),
              location[:120])
        if location.startswith("http"):
            status, _, content = http_request(location)
            check("presigned fetch 200 + bytes equal",
                  status == 200 and content == PDF_BYTES, f"got {status}")

    status, _, _ = http_request(
        f"{manager}/api/v1/conversations/{session_id}/files/{uuid.uuid4()}")
    check("missing file 404", status == 404, f"got {status}")

    if not args.skip_best_effort:
        print("=== best-effort 层（真模型生成并上传，最长 %ss）===" % args.best_effort_timeout)
        status, _, body = http_request(
            f"{manager}/api/v1/conversations/{session_id}", token=token)
        version = json.loads(body).get("version") if status == 200 else None
        message_body = {"content": (
            "请生成一个文本文件 hello-acceptance.txt，内容为一行 ok，"
            "然后调用 upload_file 工具上传，并在回复中给出下载链接。")}
        if version is not None:
            message_body["expectedVersion"] = version
        status, _, body = http_request(
            f"{manager}/api/v1/conversations/{session_id}/messages", "POST",
            token=token, idempotency_key=f"l5-file-delivery-msg-{session_id}",
            json_body=message_body)
        check("message accepted", status == 200, f"got {status} {body[:200]}")
        marker = f"/api/v1/conversations/{session_id}/files/"
        link = None
        deadline = time.time() + args.best_effort_timeout
        while time.time() < deadline and link is None:
            status, _, body = http_request(
                f"{manager}/api/v1/conversations/{session_id}/history", token=token)
            text = body.decode("utf-8", "replace")
            if marker in text:
                start = text.index(marker)
                link = text[start:start + len(marker) + 36].split('"')[0]
            else:
                time.sleep(20)
        if link is None:
            NOTES.append("best-effort：真模型未在时限内交付上传链接（记 NOTE，不算失败）")
        else:
            status, headers, _ = http_request(manager + link)
            if status == 302 and headers.get("Location", "").startswith("http"):
                status, _, content = http_request(headers["Location"])
                check("best-effort delivered file downloadable",
                      status == 200 and b"ok" in content, f"got {status}")
            else:
                check("best-effort delivered file downloadable", False,
                      f"link {link} got {status}")

    print()
    for note in NOTES:
        print("NOTE:", note)
    if FAILURES:
        print(f"FAIL：{len(FAILURES)} 项失败：{FAILURES}")
        return 1
    print("PASS：会话文件交付验收通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **步骤 2：语法检查**

运行：`python3 -m py_compile scripts/run-l5-conversation-file-delivery.py`
预期：无输出（编译通过）。脚本在任务 9 部署完成后才会真正跑到 PASS。

- [ ] **步骤 3：全量回归**

运行：`mvn -T 1C test`（14 模块全量，manager 新测试已含其中）
预期：BUILD SUCCESS。
运行：`python3 scripts/test_agentteams_task_mcp.py`
预期：全部 PASS。
console 无改动（本设计零前端变更，链接是消息文本中的 markdown），不需 console 回归。

- [ ] **步骤 4：Commit**

```bash
git add scripts/run-l5-conversation-file-delivery.py
git commit -m "test(l5): 新增 L5 会话文件交付端到端验收脚本"
```

---

### 任务 9：L5 部署与端到端验收跑通

**文件：**
- 创建：`deploy/docker/manager.runtime-overlay.Dockerfile`

沿用二期验证过的 L5 镜像分发链（本机原生打包 → overlay amd64 → 本地 registry 5500 →
k3s mirror 拉取），新 tag 不受 IfNotPresent 旧缓存影响。

- [ ] **步骤 1：新建 overlay Dockerfile**

`deploy/docker/manager.runtime-overlay.Dockerfile`（运行时段与 manager.Dockerfile 一致，
仅去掉 maven 构建段）：

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY agentteams-manager-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
```

- [ ] **步骤 2：本机原生打包（平台无关产物）**

```bash
mvn -pl manager -am -DskipTests install
mvn -pl manager -DskipTests package spring-boot:repackage
unzip -p manager/target/agentteams-manager-0.1.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep Main-Class
```

预期：`Main-Class: org.springframework.boot.loader.launch.JarLauncher`
（pom 无 repackage 绑定，缺显式 repackage 会得到 thin jar，启动报
"no main manifest attribute"——control-plane 同款坑）。

- [ ] **步骤 3：amd64 overlay 构建并推本地 registry**

```bash
ipconfig getifaddr en0          # 确认本机 IP（应仍为 192.168.125.227，若漂移需同步 L5 /etc/rancher/k3s/registries.yaml）
docker ps | grep l5registry      # registry 容器在跑（5500 端口）
STAGE=$(mktemp -d) \
  && cp manager/target/agentteams-manager-0.1.0-SNAPSHOT.jar "$STAGE/" \
  && docker build --platform linux/amd64 -f deploy/docker/manager.runtime-overlay.Dockerfile \
       -t agentteams-manager:l5-file-20260910 "$STAGE" && rm -rf "$STAGE"
docker tag agentteams-manager:l5-file-20260910 \
  192.168.125.227:5500/library/agentteams-manager:l5-file-20260910
docker push 192.168.125.227:5500/library/agentteams-manager:l5-file-20260910
```

（repo 必须 `library/` 前缀，否则 k3s mirror 命中后 404 → fallback daocloud 报误导性 403。）

- [ ] **步骤 4：L5 滚动更新 manager 镜像 + 补 storage env**

```bash
ssh ly@192.168.122.55 "kubectl -n agentteams set image \
  deployment/agentteams-agentteams-java-manager manager=agentteams-manager:l5-file-20260910 \
  && kubectl -n agentteams rollout status deployment/agentteams-agentteams-java-manager --timeout=180s"
```

storage env 取 control-plane 当前生效值（endpoint/presignEndpoint/region/bucket 直接读
env，两个 key 从 secret 解码）：

```bash
ssh ly@192.168.122.55 "kubectl -n agentteams get deploy \
  agentteams-agentteams-java-control-plane -o json" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); [print(e['name'],'=',e.get('value',json.dumps(e.get('valueFrom',{})))) for e in d['spec']['template']['spec']['containers'][0].get('env',[]) if e['name'].startswith('AGENTTEAMS_STORAGE')]"
```

然后（用上一步读到的字面量替换占位）：

```bash
ssh ly@192.168.122.55 "kubectl -n agentteams set env \
  deployment/agentteams-agentteams-java-manager \
  AGENTTEAMS_STORAGE_ENABLED=true \
  AGENTTEAMS_STORAGE_ENDPOINT=<cp 同值> \
  AGENTTEAMS_STORAGE_PRESIGN_ENDPOINT=<cp 同值> \
  AGENTTEAMS_STORAGE_REGION=<cp 同值> \
  AGENTTEAMS_STORAGE_BUCKET=<cp 同值> \
  AGENTTEAMS_STORAGE_ACCESS_KEY=<secret 解码值> \
  AGENTTEAMS_STORAGE_SECRET_KEY=<secret 解码值> \
  && kubectl -n agentteams rollout status deployment/agentteams-agentteams-java-manager --timeout=180s"
```

- [ ] **步骤 5：同步 qwenpaw MCP ConfigMap 并重启**

```bash
scp deploy/kind-qwenpaw-task-mcp.yaml ly@192.168.122.55:/tmp/
ssh ly@192.168.122.55 "kubectl apply -f /tmp/kind-qwenpaw-task-mcp.yaml \
  && kubectl -n agentteams rollout restart deployment/qwenpaw \
  && kubectl -n agentteams rollout status deployment/qwenpaw --timeout=180s \
  && rm -f /tmp/kind-qwenpaw-task-mcp.yaml"
```

（不重启则 MCP server 进程仍是旧脚本；若 qwenpaw 的 MCP client 显示 inactive，
再 rollout restart 一次——一期已知坑。）

- [ ] **步骤 6：跑验收直到 PASS**

```bash
python3 scripts/run-l5-conversation-file-delivery.py
```

预期：确定性层 7 项全 PASS；best-effort 层交付则验证下载，超时记 NOTE。
失败修复对照：401/403 → 检查 manager 的 audience/issuer 配置与 token aud claim；
503 → manager storage env 未生效（`kubectl exec … env | grep STORAGE`）；
302 Location 不可达 → 检查 L5 storage.presignEndpoint 是否为浏览器可达地址
（验收脚本从 Mac 直连 presigned URL，天然验证浏览器可达性）。
最后浏览器人工核验：在 L5 Console 会话中让 AI 生成一个文件，点击回复中的链接可下载。

- [ ] **步骤 7：Commit**

```bash
git add deploy/docker/manager.runtime-overlay.Dockerfile
git commit -m "feat(deploy): 新增 manager 运行时 overlay 镜像（交叉构建 amd64 用）"
```
