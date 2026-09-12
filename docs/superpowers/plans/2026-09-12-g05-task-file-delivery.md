# G05 任务文件交付与交付可靠性实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 任务产出/输入的真实二进制文件（PDF/图片/Office）经 MCP 工具进入任务域账本 `task_files`，交付失败可重试且可观测，G05 在本批收口。

**架构：** control-plane 新增 `task_files` 账本（role=INPUT/OUTPUT），上传/下载全服务端代理流（浏览器受众另给 302 出口）；MCP 脚本新增 `upload_task_file`/`download_task_file` 与本地失败重试队列；runtime `promptText()` 注入附件清单与产物上传引导；对账 Job 仿 `ArtifactRetentionCleanupJob` 挂 `SchedulerLeaseService` 租约。不碰 gRPC artifact 协议与 manifest 写入方（G02 D2 单一写入方原则）。

**技术栈：** Spring Boot 3 / Java 21（control-plane、manager、storage、runtime 模块）、Python 3 stdlib（MCP 脚本）、MinIO（minio-java）、Flyway（PostgreSQL V94）、junit5+Mockito+MockMvc。

**规格：** `docs/superpowers/specs/2026-09-12-g05-task-file-delivery-design.md`

**对规格的偏离（有意决策）：**
1. 规格 §5.3 的「孤儿对象对账报告」降级为 MISSING 标记 + 数量统计日志。理由：`ObjectStorage` 无列举原语，为低概率孤儿（服务端直传链路 DB 落库失败才产生，且已有 WARN）引入 `listObjects` 新接口面不划算（YAGNI）。若后续需要，在 `ObjectStorage` 加 `listByPrefix` 再补。
2. 规格 §4.1 的 attempt 解析口径细化为「`persistence.findTaskExecution(taskId)` 中 `createdAt` 最大的 attempt（无则空）」，与「最近创建的一条」语义一致且不依赖 `findByTaskId` 排序。

**前置事实（实现者必读）：**
- 鉴权模式照抄 [SubtaskController.java](control-plane/src/main/java/io/agentteams/controlplane/api/SubtaskController.java)：先 `tasks.get(id)`（未知 → `ResourceNotFoundException` → 404），有 principal 时 `PrincipalContext.requireScope(task.specJson())`；写操作要求 `Idempotency-Key` 头存在性（协议校验，无去重语义）。
- 全局错误映射在 `ApiErrorHandler`：`IllegalArgumentException` → 400、`ResourceNotFoundException` → 404。**无** `IllegalStateException` → 503 映射——503/413 由 controller 类内 `@ExceptionHandler` 提供（manager `ConversationFileController:71-80` 先例，TaskFileController 照抄）。
- 持久层有两条路：`FoundationPersistenceService.inTransaction(tx -> ...)`（域事务）与独立 `JdbcTemplate` repository（`JdbcArtifactRetentionRepository` 先例）。本计划 `task_files` 走独立 `JdbcTaskFileRepository`（不动 `FoundationTransaction` 大文件）。
- MCP 脚本容错三律：配置缺失软失败、`URLError`→`RuntimeError`、stdio loop 兜底永不退出。unittest 用 `python3 scripts/test_agentteams_task_mcp.py` 直接跑（文件尾部 `unittest.main()`，非 pytest）。
- presigned URL 只签给浏览器受众（302 出口）；集群内组件一律代理流（pitfall：presignEndpoint 是浏览器地址，pod 内不可达）。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `storage/src/main/java/io/agentteams/storage/ObjectStorage.java` | +`exists` default 方法 |
| `storage/src/main/java/io/agentteams/storage/MinioObjectStorage.java` | +`exists`（statObject，NoSuchKey→false） |
| `control-plane/src/main/resources/db/migration/V94__task_files.sql` | task_files 表 |
| `control-plane/src/main/java/io/agentteams/controlplane/taskfile/TaskFileRecord.java` | 账本行 record |
| `control-plane/src/main/java/io/agentteams/controlplane/taskfile/JdbcTaskFileRepository.java` | JdbcTemplate CRUD + 去重查询 |
| `control-plane/src/main/java/io/agentteams/controlplane/taskfile/TaskFileService.java` | 上传/登记/清单/内容/对账业务 |
| `control-plane/src/main/java/io/agentteams/controlplane/api/TaskFileController.java` | 5 端点 |
| `control-plane/src/main/java/io/agentteams/controlplane/api/TaskProcessController.java` | result 响应聚合 taskFiles |
| `control-plane/src/main/java/io/agentteams/controlplane/taskfile/TaskFileReconciliationJob.java` | 定时 MISSING 对账 |
| `control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java` | Job @Bean 装配 |
| `control-plane/src/main/resources/application.yml` | multipart 上限 + 对账间隔 |
| `manager/src/main/java/io/agentteams/manager/conversation/ConversationFileService.java` | +downloadContent |
| `manager/src/main/java/io/agentteams/manager/api/ConversationFileController.java` | +content 端点 |
| `scripts/agentteams-task-mcp.py` | 2 新工具 + 2 扩展 + 失败队列 |
| `scripts/test_agentteams_task_mcp.py` | 新工具 unittest |
| `runtime/src/main/java/io/agentteams/runtime/QwenPawHttpRuntimePort.java` | promptText 注入 |
| `runtime/src/test/java/io/agentteams/runtime/QwenPawHttpRuntimePortTest.java` | 注入用例 |
| `openapi/agentteams-public.yaml` | 端点与 schema 补录 |
| `scripts/run-kind-task-file-delivery.py` | kind 确定性层验收 |
| `scripts/run-l5-task-file-delivery.py` | L5 真模型 best-effort 验收（ClusterIP 直连） |

---

### 任务 1：storage `exists` + V94 迁移 + TaskFileRecord + JdbcTaskFileRepository

**文件：**
- 修改：`storage/src/main/java/io/agentteams/storage/ObjectStorage.java`
- 修改：`storage/src/main/java/io/agentteams/storage/MinioObjectStorage.java`
- 创建：`control-plane/src/main/resources/db/migration/V94__task_files.sql`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/taskfile/TaskFileRecord.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/taskfile/JdbcTaskFileRepository.java`
- 测试：`storage/src/test/java/io/agentteams/storage/ObjectStorageExistsDefaultTest.java`、`control-plane/src/test/java/io/agentteams/controlplane/taskfile/JdbcTaskFileRepositoryTest.java`

- [ ] **步骤 1：编写 default `exists` 的失败测试**

storage 模块测试目录若不存在则创建。测试验证 default 实现的 download 探测语义（fake 实现无需改）：

```java
package io.agentteams.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ObjectStorageExistsDefaultTest {
    private static final class Present implements ObjectStorage {
        public void upload(String k, InputStream c, long l, String t) { }
        public InputStream download(String k) { return new ByteArrayInputStream(new byte[1]); }
        public void delete(String k) { }
        public URL presignGet(String k, Duration e) { return null; }
        public URL presignPut(String k, String t, Duration e) { return null; }
    }

    private static final class Absent implements ObjectStorage {
        public void upload(String k, InputStream c, long l, String t) { }
        public InputStream download(String k) { throw new ObjectStorageException("missing", null); }
        public void delete(String k) { }
        public URL presignGet(String k, Duration e) { return null; }
        public URL presignPut(String k, String t, Duration e) { return null; }
    }

    @Test
    void defaultExistsProbesViaDownload() {
        assertThat(new Present().exists("a")).isTrue();
        assertThat(new Absent().exists("a")).isFalse();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -pl storage test -Dtest=ObjectStorageExistsDefaultTest`
预期：编译失败，`cannot find symbol: method exists(String)`。

- [ ] **步骤 3：实现接口 default 方法与 Minio override**

`ObjectStorage.java` 在 `presignPut` 之后追加：

```java
    /** Existence probe for reconciliation; the default probes via download for in-memory fakes. */
    default boolean exists(String objectKey) {
        try (InputStream ignored = download(objectKey)) {
            return true;
        } catch (Exception error) {
            return false;
        }
    }
```

`MinioObjectStorage.java`：import 增加 `io.minio.StatObjectArgs` 与 `io.minio.errors.ErrorResponseException`，在 `delete` 之后追加：

```java
    @Override
    public boolean exists(String objectKey) {
        String key = requireObjectKey(objectKey);
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException error) {
            if ("NoSuchKey".equals(error.errorResponse().code())) {
                return false;
            }
            throw failure("stat", key, error);
        } catch (Exception error) {
            throw failure("stat", key, error);
        }
    }
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -pl storage test -Dtest=ObjectStorageExistsDefaultTest`
预期：PASS。再跑 `mvn -pl storage test` 确认零回归（default 方法不破坏既有实现类）。

- [ ] **步骤 5：写 V94 迁移**

创建 `control-plane/src/main/resources/db/migration/V94__task_files.sql`：

```sql
-- G05 任务文件账本：role=INPUT（输入附件引用快照）/ OUTPUT（产物二进制）。
-- INPUT 的对象本体留在会话域（conversation_files），storage_key 空串，不建跨域 FK。
-- UNIQUE 覆盖 OUTPUT 去重；INPUT 的 sha256 为 NULL 时 PostgreSQL 视 NULL 不等，
-- INPUT 查重由应用层 (task_id, INPUT, name, source_file_id) 完成（规格 §5.1）。
CREATE TABLE task_files (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks (id),
    attempt_id UUID REFERENCES task_attempts (id),
    role TEXT NOT NULL CHECK (role IN ('INPUT', 'OUTPUT')),
    name TEXT NOT NULL,
    content_type TEXT,
    size_bytes BIGINT NOT NULL,
    sha256 TEXT,
    storage_key TEXT NOT NULL DEFAULT '',
    source_session_id UUID,
    source_file_id UUID,
    status TEXT NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'MISSING')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT task_files_unique_output UNIQUE (task_id, role, name, sha256)
);
CREATE INDEX task_files_task_idx ON task_files (task_id);
```

- [ ] **步骤 6：创建 TaskFileRecord**

```java
package io.agentteams.controlplane.taskfile;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One row of the G05 task-file ledger; subtasks are task rows (G03) so they are covered. */
public record TaskFileRecord(UUID id, UUID taskId, UUID attemptId, String role, String name,
        String contentType, long sizeBytes, String sha256, String storageKey,
        UUID sourceSessionId, UUID sourceFileId, String status, Instant createdAt, Instant updatedAt) {

    public static final String INPUT = "INPUT";
    public static final String OUTPUT = "OUTPUT";
    public static final String AVAILABLE = "AVAILABLE";
    public static final String MISSING = "MISSING";

    public TaskFileRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        if (!INPUT.equals(role) && !OUTPUT.equals(role)) {
            throw new IllegalArgumentException("role must be INPUT or OUTPUT");
        }
        Objects.requireNonNull(name, "name");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean isInput() {
        return INPUT.equals(role);
    }
}
```

- [ ] **步骤 7：编写 JdbcTaskFileRepository 测试（mock JdbcTemplate 验证 SQL 绑定）**

```java
package io.agentteams.controlplane.taskfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcTaskFileRepositoryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private JdbcTaskFileRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JdbcTaskFileRepository(jdbc);
    }

    @Test
    void insertBindsAllColumns() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        Instant now = Instant.parse("2026-09-12T00:00:00Z");
        TaskFileRecord record = new TaskFileRecord(UUID.randomUUID(), UUID.randomUUID(), null,
                TaskFileRecord.OUTPUT, "报告.pdf", "application/pdf", 3, "ab".repeat(32),
                "tasks/t/files/f/报告.pdf", null, null, TaskFileRecord.AVAILABLE, now, now);
        assertThat(repository.insert(record)).isTrue();
    }

    @Test
    void findDedupQueriesByTaskRoleNameSha() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.<TaskFileRecord>of());
        repository.findDedup(UUID.randomUUID(), TaskFileRecord.OUTPUT, "a.pdf", "cd".repeat(32));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("role = ?", "name = ?", "sha256 = ?");
    }

    @Test
    void findByIdReturnsRecordViaMapper() {
        TaskFileRecord record = repository.recordMapper()
                .mapRow(recordFixtureResultSet(), 0);
        assertThat(record).isNotNull();
        assertThat(record.role()).isEqualTo(TaskFileRecord.OUTPUT);
    }

    private org.springframework.jdbc.support.rowset.SqlRowSetMetaData metaData() {
        return org.mockito.Mockito.mock(org.springframework.jdbc.support.rowset.SqlRowSetMetaData.class);
    }

    private java.sql.ResultSet recordFixtureResultSet() throws java.sql.SQLException {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getObject("id")).thenReturn(UUID.randomUUID());
        when(rs.getObject("task_id")).thenReturn(UUID.randomUUID());
        when(rs.getObject("attempt_id")).thenReturn(null);
        when(rs.getString("role")).thenReturn("OUTPUT");
        when(rs.getString("name")).thenReturn("a.pdf");
        when(rs.getString("content_type")).thenReturn("application/pdf");
        when(rs.getLong("size_bytes")).thenReturn(3L);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getString("sha256")).thenReturn("ab".repeat(32));
        when(rs.getString("storage_key")).thenReturn("tasks/t/files/f/a.pdf");
        when(rs.getObject("source_session_id")).thenReturn(null);
        when(rs.getObject("source_file_id")).thenReturn(null);
        when(rs.getString("status")).thenReturn("AVAILABLE");
        when(rs.getObject("created_at", Instant.class)).thenReturn(Instant.EPOCH);
        when(rs.getObject("updated_at", Instant.class)).thenReturn(Instant.EPOCH);
        return rs;
    }
}
```

（若 `jdbc.query(String, RowMapper, Object...)` 签名与项目 Spring 版本不符，改用 `query(anyString(), any(Object[].class), any(RowMapper.class))` 以编译器报错为准调整；`recordMapper()` 为 repo 暴露的包级方法，见下一步。）

- [ ] **步骤 8：实现 JdbcTaskFileRepository**

```java
package io.agentteams.controlplane.taskfile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Thin JDBC ledger for G05 task files; SQL semantics covered by kind/L5 acceptance. */
@Repository
public final class JdbcTaskFileRepository {

    private final JdbcTemplate jdbc;

    public JdbcTaskFileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(TaskFileRecord record) {
        return jdbc.update("""
                INSERT INTO task_files (id, task_id, attempt_id, role, name, content_type,
                        size_bytes, sha256, storage_key, source_session_id, source_file_id,
                        status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.id(), record.taskId(), record.attemptId(), record.role(), record.name(),
                record.contentType(), record.sizeBytes(), record.sha256(), record.storageKey(),
                record.sourceSessionId(), record.sourceFileId(), record.status(),
                record.createdAt(), record.updatedAt()) == 1;
    }

    public Optional<TaskFileRecord> findById(UUID id) {
        return jdbc.query("SELECT * FROM task_files WHERE id = ?", mapper(), id).stream().findFirst();
    }

    public List<TaskFileRecord> findByTask(UUID taskId, String role) {
        if (role == null || role.isBlank()) {
            return jdbc.query("SELECT * FROM task_files WHERE task_id = ? ORDER BY created_at, id",
                    mapper(), taskId);
        }
        return jdbc.query("SELECT * FROM task_files WHERE task_id = ? AND role = ? ORDER BY created_at, id",
                mapper(), taskId, role);
    }

    /** Dedup lookup for re-uploads with identical content (spec §5.1). */
    public Optional<TaskFileRecord> findDedup(UUID taskId, String role, String name, String sha256) {
        return jdbc.query("SELECT * FROM task_files WHERE task_id = ? AND role = ? AND name = ? AND sha256 = ?",
                mapper(), taskId, role, name, sha256).stream().findFirst();
    }

    public boolean markMissing(UUID id, Instant at) {
        return jdbc.update("UPDATE task_files SET status = 'MISSING', updated_at = ? WHERE id = ?", at, id) == 1;
    }

    /** Exposed for the unit test; production callers use the query methods above. */
    RowMapper<TaskFileRecord> recordMapper() {
        return mapper();
    }

    private RowMapper<TaskFileRecord> mapper() {
        return (ResultSet rs, int rowNum) -> new TaskFileRecord(
                rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("attempt_id", UUID.class), rs.getString("role"), rs.getString("name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("storage_key"), rs.getObject("source_session_id", UUID.class),
                rs.getObject("source_file_id", UUID.class), rs.getString("status"),
                rs.getObject("created_at", Instant.class), rs.getObject("updated_at", Instant.class));
    }
}
```

- [ ] **步骤 9：运行测试验证通过并 commit**

运行：`mvn -pl control-plane -am test -Dtest='JdbcTaskFileRepositoryTest,ObjectStorageExistsDefaultTest' -DfailIfNoTests=false`
预期：PASS（storage 模块随 `-am` 一起构建）。

```bash
git add storage/src/main/java/io/agentteams/storage/ObjectStorage.java \
        storage/src/main/java/io/agentteams/storage/MinioObjectStorage.java \
        storage/src/test/java/io/agentteams/storage/ObjectStorageExistsDefaultTest.java \
        control-plane/src/main/resources/db/migration/V94__task_files.sql \
        control-plane/src/main/java/io/agentteams/controlplane/taskfile/TaskFileRecord.java \
        control-plane/src/main/java/io/agentteams/controlplane/taskfile/JdbcTaskFileRepository.java \
        control-plane/src/test/java/io/agentteams/controlplane/taskfile/JdbcTaskFileRepositoryTest.java
git commit -m "feat(任务域): G05 任务文件账本存储层（V94+exists 探测+JDBC 仓储）"
```

---

### 任务 2：TaskFileService + TaskFileController + result 聚合

**文件：**
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/taskfile/TaskFileService.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/api/TaskFileController.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/api/TaskProcessController.java`
- 修改：`control-plane/src/main/resources/application.yml`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/taskfile/TaskFileServiceTest.java`、`control-plane/src/test/java/io/agentteams/controlplane/api/TaskFileControllerTest.java`

- [ ] **步骤 1：编写 TaskFileService 失败测试**

```java
package io.agentteams.controlplane.taskfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class TaskFileServiceTest {
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-12T00:00:00Z");

    private final ObjectStorage storage = mock(ObjectStorage.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ObjectStorage> storageProvider = mock(ObjectProvider.class);
    private final JdbcTaskFileRepository repository = mock(JdbcTaskFileRepository.class);
    private final FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
    private TaskFileService service;

    @BeforeEach
    void setUp() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        service = new TaskFileService(storageProvider, repository, persistence, () -> NOW);
    }

    @Test
    void uploadStoresComputesShaAndPersistsWithLatestAttempt() throws Exception {
        when(persistence.findTaskExecution(TASK)).thenReturn(List.of(execution(ATTEMPT, NOW.minusSeconds(60))));
        byte[] content = "pdf-bytes".getBytes();
        when(storage.upload(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenAnswer(invocation -> null);
        when(repository.findDedup(TASK, TaskFileRecord.OUTPUT, "报告.pdf", sha256Of(content)))
                .thenReturn(Optional.empty());
        when(repository.insert(any(TaskFileRecord.class))).thenReturn(true);

        TaskFileRecord uploaded = service.upload(TASK, "报告.pdf", "application/pdf", content);

        assertThat(uploaded.attemptId()).isEqualTo(ATTEMPT);
        assertThat(uploaded.sha256()).isEqualTo(sha256Of(content));
        assertThat(uploaded.storageKey()).startsWith("tasks/" + TASK + "/files/");
        assertThat(uploaded.storageKey()).endsWith("/报告.pdf");
        verify(storage).upload(eq(uploaded.storageKey()), any(InputStream.class), eq(9L), eq("application/pdf"));
        verify(repository).insert(uploaded);
    }

    @Test
    void uploadDedupReturnsExistingRecordWithoutNewObject() throws Exception {
        byte[] content = "same".getBytes();
        TaskFileRecord existing = record(TaskFileRecord.OUTPUT, "a.pdf", sha256Of(content));
        when(repository.findDedup(TASK, TaskFileRecord.OUTPUT, "a.pdf", sha256Of(content)))
                .thenReturn(Optional.of(existing));

        TaskFileRecord uploaded = service.upload(TASK, "a.pdf", "application/pdf", content);

        assertThat(uploaded).isSameAs(existing);
        verify(storage, never()).upload(anyString(), any(InputStream.class), anyLong(), anyString());
        verify(repository, never()).insert(any(TaskFileRecord.class));
    }

    @Test
    void uploadRejectsOversizeAndMissingTask() {
        byte[] big = new byte[(int) TaskFileService.MAX_FILE_BYTES + 1];
        assertThatThrownBy(() -> service.upload(TASK, "big.bin", "application/octet-stream", big))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("50MB");
        assertThatThrownBy(() -> service.registerInput(TASK, UUID.randomUUID(), UUID.randomUUID(),
                "x.pdf", 3))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void registerInputSnapshotsMetadataAndDedupsBySource() {
        when(repository.findByTask(TASK, TaskFileRecord.INPUT)).thenReturn(List.of());
        when(repository.insert(any(TaskFileRecord.class))).thenReturn(true);

        TaskFileRecord registered = service.registerInput(TASK, UUID.randomUUID(), UUID.randomUUID(),
                "输入.pdf", 3);

        assertThat(registered.role()).isEqualTo(TaskFileRecord.INPUT);
        assertThat(registered.storageKey()).isEmpty();
        assertThat(registered.attemptId()).isNull();
        verify(repository).insert(registered);
    }

    @Test
    void reconcileMarksMissingObjectsOnly() {
        TaskFileRecord ok = record(TaskFileRecord.OUTPUT, "ok.pdf", "aa");
        TaskFileRecord gone = record(TaskFileRecord.OUTPUT, "gone.pdf", "bb");
        when(repository.findByTask(TASK, null)).thenReturn(List.of(ok, gone));
        when(storage.exists(ok.storageKey())).thenReturn(true);
        when(storage.exists(gone.storageKey())).thenReturn(false);

        service.reconcile(TASK);

        verify(repository).markMissing(gone.id(), NOW);
        verify(repository, never()).markMissing(eq(ok.id()), any());
    }

    private FoundationPersistenceService.TaskExecutionRecord execution(UUID attemptId, Instant createdAt) {
        TaskAttemptRecord attempt = new TaskAttemptRecord(attemptId, TASK, UUID.randomUUID(),
                io.agentteams.domain.task.TaskPhase.RUNNING, NOW.plusSeconds(300), null,
                "worker-1", "gRPC", null, null, createdAt, createdAt, 0);
        return new FoundationPersistenceService.TaskExecutionRecord(attempt, null, null);
    }

    private TaskFileRecord record(String role, String name, String sha) {
        return new TaskFileRecord(UUID.randomUUID(), TASK, ATTEMPT, role, name, "application/pdf",
                3, sha, role.equals(TaskFileRecord.OUTPUT) ? "tasks/" + TASK + "/files/f/" + name : "",
                UUID.randomUUID(), UUID.randomUUID(), TaskFileRecord.AVAILABLE, NOW, NOW);
    }

    private static String sha256Of(byte[] content) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }
}
```

（`TaskExecutionRecord(attempt, assignment, lease)` 的后两位传 null 需确认 record 允许 null——该 record 仅是聚合载体无构造校验，成立。）

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -pl control-plane test -Dtest=TaskFileServiceTest`
预期：编译失败，`TaskFileService` 不存在。

- [ ] **步骤 3：实现 TaskFileService**

```java
package io.agentteams.controlplane.taskfile;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.TaskAttemptRecord;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * G05 任务文件账本业务：OUTPUT 服务端直传（先 storage 后落库，与 ConversationFileService
 * 同序）、INPUT 元数据快照登记、清单查询、MISSING 对账。run 终态不因交付失败改变
 * （过程 best-effort 公理）；去重按 (task, role, name, sha256) 返回既有记录。
 */
@Service
public final class TaskFileService {

    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private final ObjectProvider<ObjectStorage> storageProvider;
    private final JdbcTaskFileRepository repository;
    private final FoundationPersistenceService persistence;
    private final Clock clock;

    public TaskFileService(ObjectProvider<ObjectStorage> storageProvider,
            JdbcTaskFileRepository repository, FoundationPersistenceService persistence, Clock clock) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private ObjectStorage requireStorage() {
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new IllegalStateException("object storage is not enabled (agentteams.storage.enabled=false)");
        }
        return storage;
    }

    public TaskFileRecord upload(UUID taskId, String originalName, String contentType, byte[] content) {
        ObjectStorage storage = requireStorage();
        requireExistingTask(taskId);
        if (content.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("file exceeds 50MB limit");
        }
        String name = TaskFileNames.sanitize(originalName);
        String sha256 = sha256(content);
        var existing = repository.findDedup(taskId, TaskFileRecord.OUTPUT, name, sha256);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID fileId = UUID.randomUUID();
        String safeContentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        String storageKey = "tasks/" + taskId + "/files/" + fileId + "/" + name;
        storage.upload(storageKey, new ByteArrayInputStream(content), content.length, safeContentType);
        Instant now = clock.instant();
        TaskFileRecord record = new TaskFileRecord(fileId, taskId, latestAttemptId(taskId),
                TaskFileRecord.OUTPUT, name, safeContentType, content.length, sha256, storageKey,
                null, null, TaskFileRecord.AVAILABLE, now, now);
        repository.insert(record);
        return record;
    }

    public TaskFileRecord registerInput(UUID taskId, UUID sessionId, UUID sourceFileId,
            String name, long sizeBytes) {
        requireExistingTask(taskId);
        String safeName = TaskFileNames.sanitize(name);
        var existing = repository.findByTask(taskId, TaskFileRecord.INPUT).stream()
                .filter(item -> safeName.equals(item.name())
                        && sourceFileId.equals(item.sourceFileId()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = clock.instant();
        TaskFileRecord record = new TaskFileRecord(UUID.randomUUID(), taskId, null,
                TaskFileRecord.INPUT, safeName, null, sizeBytes, null, "",
                sessionId, sourceFileId, TaskFileRecord.AVAILABLE, now, now);
        repository.insert(record);
        return record;
    }

    public List<TaskFileRecord> list(UUID taskId, String role) {
        requireExistingTask(taskId);
        return repository.findByTask(taskId, role);
    }

    public TaskFileRecord get(UUID taskId, UUID fileId) {
        requireExistingTask(taskId);
        return repository.findById(fileId)
                .filter(record -> record.taskId().equals(taskId))
                .orElseThrow(() -> new ResourceNotFoundException("task file", fileId));
    }

    /** Streams an OUTPUT object; INPUT records never reach here（controller 409 门卫）。 */
    public InputStream outputContent(TaskFileRecord record) {
        if (record.isInput()) {
            throw new IllegalArgumentException("input attachments live in the conversation domain");
        }
        return requireStorage().download(record.storageKey());
    }

    /** Browser-audience presigned GET（presignEndpoint 受众）；storage 未启用时 503。 */
    public java.net.URL presignForBrowser(TaskFileRecord record, Duration expiry) {
        return requireStorage().presignGet(record.storageKey(), expiry);
    }

    public void reconcile(UUID taskId) {
        List<TaskFileRecord> files = repository.findByTask(taskId, null);
        if (files.isEmpty()) {
            return;
        }
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return;
        }
        Instant now = clock.instant();
        for (TaskFileRecord record : files) {
            if (record.isInput() || TaskFileRecord.MISSING.equals(record.status())) {
                continue;
            }
            if (!storage.exists(record.storageKey())) {
                repository.markMissing(record.id(), now);
            }
        }
    }

    private void requireExistingTask(UUID taskId) {
        // 轻量存在性校验：复用 FoundationPersistenceService，避免整 spec 解析开销。
        if (persistence.findTask(taskId).isEmpty()) {
            throw new ResourceNotFoundException("task", taskId);
        }
    }

    /** 最近创建的 attempt（无则空）；以 createdAt 比较避免依赖 findByTaskId 排序（规格偏离 2）。 */
    private UUID latestAttemptId(UUID taskId) {
        return persistence.findTaskExecution(taskId).stream()
                .map(FoundationPersistenceService.TaskExecutionRecord::attempt)
                .max(Comparator.comparing(TaskAttemptRecord::createdAt))
                .map(TaskAttemptRecord::id)
                .orElse(null);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
```

同名伴生工具类 `TaskFileNames.java`（清洗逻辑与 manager `ConversationFileService.sanitizeName` 同规则，注释注明来源）：

```java
package io.agentteams.controlplane.taskfile;

/** Mirrors ConversationFileService.sanitizeName: last path segment, control chars stripped, 255 cap. */
final class TaskFileNames {
    private TaskFileNames() { }

    static String sanitize(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isBlank()) {
            return "file";
        }
        return name.length() <= 255 ? name : name.substring(name.length() - 255);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -pl control-plane test -Dtest=TaskFileServiceTest`
预期：PASS（6 用例）。

- [ ] **步骤 5：编写 TaskFileController 失败测试**

```java
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
import java.util.Set;
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
}
```

- [ ] **步骤 6：运行测试验证失败，然后实现 TaskFileController**

运行：`mvn -pl control-plane test -Dtest=TaskFileControllerTest`
预期：编译失败（`TaskFileController` 不存在）。

```java
package io.agentteams.controlplane.api;

import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.controlplane.taskfile.TaskFileRecord;
import io.agentteams.controlplane.taskfile.TaskFileService;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * G05 任务文件端点：上传/登记/清单/内容/浏览器 302。鉴权沿 SubtaskController 模式
 * ——未知任务 404，有 principal 时按任务 specJson 校验作用域；写操作要求幂等键
 * （协议存在性校验）。浏览器 302 出口用 presignEndpoint（浏览器受众）；集群内组件
 * 一律走 /content 代理流（presigned 受众 pitfall）。
 */
@RestController
@RequestMapping("/api/v1/tasks/{taskId}/files")
public final class TaskFileController {

    private static final Duration BROWSER_PRESIGN_TTL = Duration.ofMinutes(15);

    private final TaskFileService files;
    private final TaskService tasks;

    public TaskFileController(TaskFileService files, TaskService tasks) {
        this.files = files;
        this.tasks = tasks;
    }

    /** OUTPUT multipart 上传（服务端直传 MinIO，先对象后落库）。 */
    @PostMapping
    public ResponseEntity<TaskFileResponse> upload(@PathVariable UUID taskId,
            @RequestPart("file") MultipartFile part) throws Exception {
        requireExistingTaskScope(taskId);
        TaskFileRecord record = files.upload(taskId, part.getOriginalFilename(),
                part.getContentType(), part.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/api/v1/tasks/" + taskId + "/files/" + record.id())
                .body(TaskFileResponse.from(record));
    }

    /** INPUT 登记快照（JSON，幂等键必带）。 */
    @PostMapping("/attachments")
    public ResponseEntity<AttachmentListResponse> registerAttachments(@PathVariable UUID taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AttachmentsRequest request) {
        requireExistingTaskScope(taskId);
        requireIdempotencyKey(idempotencyKey);
        if (request == null || request.attachments() == null) {
            throw new IllegalArgumentException("attachments is required");
        }
        List<TaskFileResponse> registered = request.attachments().stream()
                .map(item -> TaskFileResponse.from(files.registerInput(taskId, item.sessionId(),
                        item.fileId(), item.name(), item.sizeBytes())))
                .toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(new AttachmentListResponse(registered));
    }

    @GetMapping
    public List<TaskFileResponse> list(@PathVariable UUID taskId,
            @RequestParam(required = false) String role) {
        requireExistingTaskScope(taskId);
        return files.list(taskId, role).stream().map(TaskFileResponse::from).toList();
    }

    /** 集群内代理流下载（MCP/汇总轮受众）。INPUT 记录 409（对象在会话域）。 */
    @GetMapping("/{fileId}/content")
    public ResponseEntity<StreamingResponseBody> content(@PathVariable UUID taskId,
            @PathVariable UUID fileId) {
        requireExistingTaskScope(taskId);
        TaskFileRecord record = files.get(taskId, fileId);
        if (record.isInput()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        InputStream stream = files.outputContent(record);
        StreamingResponseBody body = output -> stream.transferTo(output);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(body);
    }

    /** 浏览器 302 出口（presignEndpoint 受众，与 artifacts downloadUrl 同款）；仅 OUTPUT。 */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<Void> download(@PathVariable UUID taskId, @PathVariable UUID fileId) {
        requireExistingTaskScope(taskId);
        TaskFileRecord record = files.get(taskId, fileId);
        if (record.isInput()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        java.net.URL presigned = files.presignForBrowser(record, BROWSER_PRESIGN_TTL);
        return ResponseEntity.status(HttpStatus.FOUND).location(presigned.toURI()).build();
    }

    /** SubtaskController.requireExistingTaskScope 同款（未知任务 404，有 principal 校验作用域）。 */
    private void requireExistingTaskScope(UUID taskId) {
        TaskRecord task = tasks.get(taskId);
        if (PrincipalContext.current().isPresent()) {
            PrincipalContext.requireScope(task.specJson());
        }
    }

    /** SubtaskController.requireIdempotencyKey 同款（协议存在性校验，无去重语义）。 */
    private static void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        if (idempotencyKey.length() > 255) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 255 characters");
        }
    }

    /** Storage disabled (or misconfigured) degrades to an explicit 503（ConversationFileController 同款）。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Void> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    /** Multipart over 50MB degrades to an explicit 413（spec §6；application.yml 上限由容器抛出）。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(
            org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Void> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
    }

    public record AttachmentsRequest(List<AttachmentInput> attachments) {
    }

    public record AttachmentInput(UUID sessionId, UUID fileId, String name, long sizeBytes) {
    }

    public record AttachmentListResponse(List<TaskFileResponse> files) {
    }

    public record TaskFileResponse(UUID fileId, String role, String name, String contentType,
            long sizeBytes, String sha256, String status, UUID sessionId, UUID sourceFileId) {

        static TaskFileResponse from(TaskFileRecord record) {
            return new TaskFileResponse(record.id(), record.role(), record.name(),
                    record.contentType(), record.sizeBytes(), record.sha256(), record.status(),
                    record.sourceSessionId(), record.sourceFileId());
        }
    }
}
```

（controller 直接依赖 `TaskService` 做入口存在性/作用域校验，`TaskFileService.requireExistingTask` 保留为服务层兜底——两层防御。`TaskService` 为 final class 但项目 Mockito 配置已支持 mock（SubtaskControllerTest:41 `mock(TaskService.class)` 先例）。）

- [ ] **步骤 7：result 响应聚合 taskFiles**

`TaskProcessController.java`：字段/构造器/兼容构造器与 record 扩展——

```java
    // 主构造器追加参数 TaskFileService taskFiles 并赋字段 this.taskFiles = taskFiles;
    // 兼容构造器（TaskProcessEventService, TaskProgressService, TaskResultManifestService,
    //   ExecutionContextResolver, ObjectProvider<ArtifactService>）追加 this(events, progress,
    //   results, contextResolver, artifactService, null);
    // record 追加字段：
    public record TaskResultResponse(String taskId, String runId, String status, String summary,
            List<ResultArtifact> artifacts, List<TaskFileSummary> taskFiles) {
    }

    public record TaskFileSummary(UUID fileId, String role, String name, String contentType,
            long sizeBytes, String status, UUID sessionId, UUID sourceFileId) {
    }
```

`result()` 方法在构建 `TaskResultResponse` 时追加（`taskFiles == null` 时取 `List.of()`，保持兼容构造器行为）：

```java
        List<TaskFileSummary> taskFileSummaries = taskFiles == null ? List.of()
                : taskFiles.list(taskId, null).stream()
                        .map(record -> new TaskFileSummary(record.id(), record.role(), record.name(),
                                record.contentType(), record.sizeBytes(), record.status(),
                                record.sourceSessionId(), record.sourceFileId()))
                        .toList();
        return new TaskResultResponse(..., taskFileSummaries);
```

同步修正 control-plane 测试中直接构造 `TaskResultResponse` / `TaskProcessController` 的编译点（`grep -rn "new TaskResultResponse(" control-plane/src` 与 `grep -rn "new TaskProcessController(" control-plane/src`）。

- [ ] **步骤 8：multipart 配置**

`control-plane/src/main/resources/application.yml` 的 `spring:` 段内加入（manager 同款）：

```yaml
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 55MB
```

- [ ] **步骤 9：运行测试验证通过并 commit**

运行：`mvn -pl control-plane test`
预期：全绿（新增 + 既有零回归）。

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/taskfile/ \
        control-plane/src/main/java/io/agentteams/controlplane/api/TaskFileController.java \
        control-plane/src/main/java/io/agentteams/controlplane/api/TaskProcessController.java \
        control-plane/src/main/resources/application.yml \
        control-plane/src/test/java/io/agentteams/controlplane/taskfile/TaskFileServiceTest.java \
        control-plane/src/test/java/io/agentteams/controlplane/api/TaskFileControllerTest.java
git commit -m "feat(任务域): G05 任务文件上传/登记/清单/内容端点与 result 聚合"
```

---

### 任务 3：manager 会话文件 content 代理流端点

**文件：**
- 修改：`manager/src/main/java/io/agentteams/manager/conversation/ConversationFileService.java`
- 修改：`manager/src/main/java/io/agentteams/manager/api/ConversationFileController.java`
- 测试：`manager/src/test/java/io/agentteams/manager/conversation/ConversationFileServiceTest.java`、`manager/src/test/java/io/agentteams/manager/api/ConversationFileControllerTest.java`

- [ ] **步骤 1：编写失败的测试**

`ConversationFileServiceTest` 追加：

```java
    @Test
    void downloadContentStreamsStoredObject() throws Exception {
        UUID fileId = UUID.randomUUID();
        when(repository.find(SESSION, fileId)).thenReturn(new ConversationFile(fileId, SESSION,
                "a.pdf", "application/pdf", 3, "conversations/" + SESSION + "/files/" + fileId + "/a.pdf",
                Instant.EPOCH));
        when(storage.download("conversations/" + SESSION + "/files/" + fileId + "/a.pdf"))
                .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));

        ConversationFileService.DownloadContent content = service.downloadContent(SESSION, fileId);

        assertThat(content.name()).isEqualTo("a.pdf");
        assertThat(content.contentType()).isEqualTo("application/pdf");
        assertThat(content.content().readAllBytes()).isEqualTo(new byte[] {1, 2, 3});
    }

    @Test
    void downloadContentReturnsNullWhenMissing() {
        assertThat(service.downloadContent(SESSION, UUID.randomUUID())).isNull();
    }
```

`ConversationFileControllerTest` 追加：

```java
    @Test
    void contentEndpointStreamsForTokenedCallers() throws Exception {
        UUID fileId = UUID.randomUUID();
        when(files.downloadContent(SESSION, fileId)).thenReturn(
                new ConversationFileService.DownloadContent("a.pdf", "application/pdf",
                        new ByteArrayInputStream("pdf".getBytes())));
        mvc.perform(get("/api/v1/conversations/{sessionId}/files/{fileId}/content", SESSION, fileId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/pdf"));
    }

    @Test
    void contentEndpointReturns404WhenMissing() throws Exception {
        when(files.downloadContent(any(), any())).thenReturn(null);
        mvc.perform(get("/api/v1/conversations/{sessionId}/files/{fileId}/content", SESSION, UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
```

（import 需补：`java.io.ByteArrayInputStream`、`org.springframework.test.web.servlet.result.MockMvcResultMatchers.content`；MockMvc 构造沿用该测试类既有 `standaloneSetup` 写法。）

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -pl manager test -Dtest='ConversationFileServiceTest,ConversationFileControllerTest'`
预期：编译失败，`downloadContent` 不存在。

- [ ] **步骤 3：实现**

`ConversationFileService` 在 `presignDownload` 之后追加：

```java
    /** Streams the stored object for cluster-internal consumers (MCP input download). */
    public DownloadContent downloadContent(UUID sessionId, UUID fileId) {
        ObjectStorage storage = requireStorage();
        ConversationFile file = repository.find(sessionId, fileId);
        if (file == null) {
            return null;
        }
        return new DownloadContent(file.name(), file.contentType(), storage.download(file.storageKey()));
    }

    public record DownloadContent(String name, String contentType, InputStream content) { }
```

`ConversationFileController` 在 `download` 之后追加：

```java
    /** Cluster-internal streaming download (tokened callers, e.g. MCP download_task_file). */
    @GetMapping("/{sessionId}/files/{fileId}/content")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> downloadContent(
            @PathVariable UUID sessionId, @PathVariable UUID fileId) {
        ConversationFileService.DownloadContent content = files.downloadContent(sessionId, fileId);
        if (content == null) {
            return ResponseEntity.notFound().build();
        }
        String contentType = content.contentType() == null || content.contentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : content.contentType();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
                        + content.name().replace("\"", "'") + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(new org.springframework.core.io.InputStreamResource(content.content()));
    }
```

（import 需补：`org.springframework.http.HttpHeaders`；该类匿名下载端点 `GET /{sid}/files/{fid}` 的 `ManagerAuthenticationFilter` 放行不受影响——`/content` 子路径不在放行清单中，token 鉴权生效，符合设计。）

- [ ] **步骤 4：运行测试验证通过并 commit**

运行：`mvn -pl manager test`
预期：全绿。

```bash
git add manager/src/main/java/io/agentteams/manager/conversation/ConversationFileService.java \
        manager/src/main/java/io/agentteams/manager/api/ConversationFileController.java \
        manager/src/test/java/io/agentteams/manager/conversation/ConversationFileServiceTest.java \
        manager/src/test/java/io/agentteams/manager/api/ConversationFileControllerTest.java
git commit -m "feat(manager): 会话文件 content 代理流端点（G05 输入附件下载）"
```

### 任务 4：MCP 脚本 upload_task_file / download_task_file / attachments / result 聚合 / 失败队列

**文件：**
- 修改：`scripts/agentteams-task-mcp.py`
- 修改：`scripts/test_agentteams_task_mcp.py`
- 修改（契约守护的同步副本）：`deploy/kind-qwenpaw-task-mcp.yaml`

**前置事实：** `test_deploy_configmap_embeds_current_script`（test_agentteams_task_mcp.py:469）断言 ConfigMap 内嵌脚本与源文件逐字一致——脚本改动后必须重生成 ConfigMap，否则 unittest 红。`test_stdio_subprocess_roundtrip` 断言 `tools/list` 返回 **7** 个工具——本任务后改为 9。`_http_multipart`（agentteams-task-mcp.py:573）无幂等键参数，需扩展。

- [ ] **步骤 1：编写失败的测试**

`test_agentteams_task_mcp.py` 追加/修改（新测试类或并入既有类均可，保持该文件单类风格则并入）。先给 stub 扩展分支：

`ControlPlaneStub.do_POST` 在 `/queue` 分支后追加：

```python
        elif self.path.endswith("/attachments"):
            self.server.requests.append(("POST", self.path, dict(self.headers), payload))
            self._reply({"files": [
                {"fileId": str(uuid.uuid4()), "role": "INPUT", "name": item.get("name"),
                 "sizeBytes": item.get("sizeBytes"), "status": "AVAILABLE"}
                for item in payload.get("attachments", [])
            ]}, status=201)
        elif self.path.endswith("/files"):
            self.server.requests.append(("POST", self.path, dict(self.headers), raw))
            if getattr(self.server, "fail_uploads", False):
                self._reply({"error": "storage unavailable"}, status=503)
                return
            self.server.task_upload_body = raw
            self._reply({"fileId": str(uuid.uuid4()), "name": "报告.pdf",
                         "sizeBytes": len(raw), "sha256": "ab" * 32}, status=201)
```

`ControlPlaneStub.do_GET`（既有 runs/result 分支保留）追加：

```python
        if self.path.endswith("/files"):
            self.server.requests.append(("GET", self.path, dict(self.headers), None))
            self._reply(list(getattr(self.server, "task_files", [])))
        elif "/content" in self.path:
            self.server.requests.append(("GET", self.path, dict(self.headers), None))
            body = getattr(self.server, "content_bytes", b"PDFBYTES")
            self.send_response(200)
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
```

`ManagerStub` 追加（若该类已有 `do_GET` 则并入既有方法）：

```python
    def do_GET(self):
        if "/content" in self.path:
            self.server.requests.append(("GET", self.path, dict(self.headers), None))
            body = b"INPUT-PDF"
            self.send_response(200)
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
```

新用例（`setUp` 里补 `self.workspace = tempfile.mkdtemp()` 并在 env 字典加
`"AGENTTEAMS_WORKSPACE_DIR": self.workspace`；`tearDown` 里 `shutil.rmtree(self.workspace, ignore_errors=True)`——tempfile/shutil 该文件已 import）：

```python
    def test_upload_task_file_queues_on_failure_and_flushes_on_next_call(self):
        source = Path(self.workspace) / "报告.pdf"
        source.write_bytes(b"%PDF-1.4 fake")
        self.server.fail_uploads = True
        response = rpc({"jsonrpc": "2.0", "id": 20, "method": "tools/call", "params": {
            "name": "upload_task_file",
            "arguments": {"task_id": self.server.task_id, "path": "报告.pdf"}}})
        self.assertTrue(response["result"]["isError"])
        self.assertIn('"queued": true', response["result"]["content"][0]["text"])
        queue_path = Path(self.workspace) / ".task-upload-queue.json"
        queue = json.loads(queue_path.read_text())
        self.assertEqual(queue[0]["status"], "PENDING")
        self.assertEqual(queue[0]["taskId"], self.server.task_id)

        self.server.fail_uploads = False
        response = rpc({"jsonrpc": "2.0", "id": 21, "method": "tools/call", "params": {
            "name": "upload_task_file",
            "arguments": {"task_id": self.server.task_id, "path": "报告.pdf"}}})
        self.assertFalse(response["result"]["isError"])
        self.assertIn('"fileId"', response["result"]["content"][0]["text"])
        self.assertFalse(queue_path.exists())

    def test_download_task_file_output_streams_to_workspace(self):
        self.server.task_files = [{"fileId": self.server.file_id, "role": "OUTPUT",
                                   "name": "top10.pdf", "contentType": "application/pdf",
                                   "sizeBytes": 8, "status": "AVAILABLE"}]
        response = rpc({"jsonrpc": "2.0", "id": 22, "method": "tools/call", "params": {
            "name": "download_task_file",
            "arguments": {"task_id": self.server.task_id, "file_id": self.server.file_id}}})
        self.assertFalse(response["result"]["isError"])
        payload = json.loads(response["result"]["content"][0]["text"])
        downloaded = Path(payload["path"])
        self.assertTrue(str(downloaded).startswith(str(Path(self.workspace).resolve())))
        self.assertEqual(downloaded.read_bytes(), b"PDFBYTES")

    def test_download_task_file_input_uses_manager_content(self):
        session_id = str(uuid.uuid4())
        self.server.task_files = [{"fileId": self.server.file_id, "role": "INPUT",
                                   "name": "输入.pdf", "sessionId": session_id,
                                   "sourceFileId": str(uuid.uuid4()), "sizeBytes": 9,
                                   "status": "AVAILABLE"}]
        response = rpc({"jsonrpc": "2.0", "id": 23, "method": "tools/call", "params": {
            "name": "download_task_file",
            "arguments": {"task_id": self.server.task_id, "file_id": self.server.file_id}}})
        self.assertFalse(response["result"]["isError"])
        gets = [path for method, path, *_ in self.server.requests if method == "GET"]
        self.assertTrue(any("/api/v1/conversations/" + session_id in path for path in gets))
        self.assertTrue(any("/files" in path and "content" in path for path in gets))

    def test_create_task_with_attachments_projects_into_input_json(self):
        session_id = str(uuid.uuid4())
        source_file_id = str(uuid.uuid4())
        response = rpc({"jsonrpc": "2.0", "id": 24, "method": "tools/call", "params": {
            "name": "create_task",
            "arguments": {"title": "分析报告", "prompt": "读取附件并汇总",
                          "attachments": [{"sessionId": session_id, "fileId": source_file_id,
                                           "name": "输入.pdf", "sizeBytes": 123}]}}})
        self.assertFalse(response["result"]["isError"])
        create = self.server.create_body
        self.assertEqual(create["spec"]["inputJson"]["attachments"],
                         [{"sessionId": session_id, "fileId": source_file_id,
                           "name": "输入.pdf", "sizeBytes": 123}])
        ledger_posts = [body for method, path, headers, body in self.server.requests
                        if method == "POST" and path.endswith("/attachments")]
        self.assertEqual(len(ledger_posts), 1)
        self.assertIn("Idempotency-Key",
                      [headers for method, path, headers, _ in self.server.requests
                       if method == "POST" and path.endswith("/attachments")][0])

    def test_get_task_result_includes_task_files(self):
        response = rpc({"jsonrpc": "2.0", "id": 25, "method": "tools/call", "params": {
            "name": "get_task_result", "arguments": {"task_id": self.server.task_id}}})
        self.assertIn('"taskFiles"', response["result"]["content"][0]["text"])

    def test_upload_task_file_rejects_workspace_escape(self):
        response = rpc({"jsonrpc": "2.0", "id": 26, "method": "tools/call", "params": {
            "name": "upload_task_file",
            "arguments": {"task_id": self.server.task_id, "path": "../escape.pdf"}}})
        self.assertTrue(response["result"]["isError"])
        self.assertIn("workspace", response["result"]["content"][0]["text"])
```

`test_stdio_subprocess_roundtrip` 中 `self.assertEqual(len(tools["result"]["tools"]), 7)` 改为 `9`。stub 的 `setUp` 补挂载：`self.server.task_id = str(uuid.uuid4())`（若已有则复用）、`self.server.file_id = str(uuid.uuid4())`、`self.server.task_files = []`。

- [ ] **步骤 2：运行测试验证失败**

运行：`python3 scripts/test_agentteams_task_mcp.py -k task_file -v`
预期：FAIL/ERROR，`unsupported tool: upload_task_file`、`KeyError: 'download_task_file'`。

- [ ] **步骤 3：实现脚本改动**

`agentteams-task-mcp.py`（全部改动如下，按插入位置排列）：

常量与 TOOL_NAMES（第 42-47 行区域）：

```python
TASK_FILE_MAX_BYTES = MAX_UPLOAD_BYTES
UPLOAD_RETRIES = 2
```

```python
TOOL_NAMES = ["create_task", "get_task", "get_task_result",
              "list_subtasks", "plan_subtasks", "update_subtask_status",
              "upload_file", "upload_task_file", "download_task_file"]
```

`TOOL_SCHEMAS` 追加两个条目（`upload_file` 条目之后）：

```python
    "upload_task_file": {
        "description": (
            "Upload a binary deliverable (PDF/image/Office) generated inside "
            "the agent workspace into the task deliverables ledger so it "
            "remains downloadable after the worker is gone. task_id comes "
            "from the [平台上下文] block. Text deliverables still go through "
            "the final JSON artifacts channel."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "task_id": {"type": "string", "description": "Task UUID."},
                "path": {"type": "string",
                         "description": "Workspace-relative path of the file."},
                "filename": {"type": "string",
                             "description": "Optional display name; defaults to basename."},
            },
            "required": ["task_id", "path"],
        },
    },
    "download_task_file": {
        "description": (
            "Download one task file into the agent workspace: input "
            "attachments (referenced conversation files) and binary "
            "deliverables alike. Returns the workspace path to read."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "task_id": {"type": "string", "description": "Task UUID."},
                "file_id": {"type": "string",
                            "description": "Task file UUID from get_task_result taskFiles."},
            },
            "required": ["task_id", "file_id"],
        },
    },
```

`_http_multipart` 签名加幂等键（与 `_http_json` 同款 header），并新增 `_http_download`：

```python
def _http_multipart(url: str, *, token: str, field: str, filename: str,
                    content: bytes, content_type: str,
                    idempotency_key: str | None = None) -> dict[str, Any]:
```

```python
    if token:
        headers["Authorization"] = "Bearer " + token
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
```

```python
def _http_download(url: str, *, token: str, destination: str) -> None:
    request = urllib.request.Request(
        url, headers={"Accept": "*/*", "Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            payload = response.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")[:300]
        raise RuntimeError(f"HTTP {error.code} from {url}: {detail}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"connection to {url} failed: {error.reason}") from error
    with open(destination, "wb") as handle:
        handle.write(payload)
```

失败重试队列（放在 `_resolve_workspace_path` 之前；队列文件与 workspace 同生命周期，pod 重启即丢，此时 workspace 源文件同样不存在，语义一致）：

```python
def _queue_path(config: Config) -> str:
    return os.path.join(config.workspace_dir, ".task-upload-queue.json")


def _load_queue(config: Config) -> list[dict[str, Any]]:
    path = _queue_path(config)
    if not os.path.exists(path):
        return []
    try:
        with open(path, encoding="utf-8") as handle:
            entries = json.load(handle)
        return entries if isinstance(entries, list) else []
    except (OSError, json.JSONDecodeError):
        return []


def _save_queue(config: Config, entries: list[dict[str, Any]]) -> None:
    path = _queue_path(config)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(entries, handle, ensure_ascii=False)


def _flush_upload_queue(config: Config) -> int:
    """Opportunistic retry before each upload_task_file call.

    Sources that vanished are marked DROPPED: the queue shares the workspace
    lifecycle, so a missing source can never deliver and must not hang forever.
    """
    entries = _load_queue(config)
    if not entries:
        return 0
    remaining: list[dict[str, Any]] = []
    delivered = 0
    for entry in entries:
        source = entry.get("filePath") or ""
        if not os.path.isfile(source):
            entry["status"] = "DROPPED"
            continue
        try:
            with open(source, "rb") as handle:
                content = handle.read()
            _http_multipart(
                _api_url(config, f"/api/v1/tasks/{entry['taskId']}/files"),
                token=_fetch_token(), field="file", filename=entry["name"],
                content=content,
                content_type=entry.get("contentType") or "application/octet-stream",
                idempotency_key=str(uuid.uuid4()))
            delivered += 1
        except RuntimeError:
            remaining.append(entry)
    _save_queue(config, remaining)
    return delivered


def _enqueue_failed_upload(config: Config, task_id: str, source: str, name: str,
                           content_type: str, size_bytes: int) -> None:
    entries = _load_queue(config)
    entries.append({"taskId": task_id, "filePath": source, "name": name,
                    "contentType": content_type, "sizeBytes": size_bytes,
                    "attempts": UPLOAD_RETRIES + 1, "status": "PENDING",
                    "enqueuedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())})
    _save_queue(config, entries)
```

两个新工具（放在 `tool_upload_file` 之后）：

```python
def tool_upload_task_file(arguments: dict[str, Any]) -> dict[str, Any]:
    config = get_config()
    task_id = _safe_uuid(arguments.get("task_id"), "task_id")
    raw_path = arguments.get("path")
    if not isinstance(raw_path, str) or not raw_path.strip():
        raise ValueError("path must be a non-empty string")
    resolved = _resolve_workspace_path(config, raw_path.strip())
    if not os.path.isfile(resolved):
        raise ValueError(f"file not found in workspace: {raw_path}")
    if os.path.getsize(resolved) > TASK_FILE_MAX_BYTES:
        raise ValueError(f"file exceeds the {TASK_FILE_MAX_BYTES} byte task upload limit")
    _flush_upload_queue(config)
    name = _clean_text(arguments.get("filename"), "filename", 255, required=False) \
        or os.path.basename(resolved) or "file"
    with open(resolved, "rb") as handle:
        content = handle.read()
    content_type = mimetypes.guess_type(resolved)[0] or "application/octet-stream"
    error: RuntimeError | None = None
    for _ in range(UPLOAD_RETRIES + 1):
        try:
            response = _http_multipart(
                _api_url(config, f"/api/v1/tasks/{task_id}/files"),
                token=_fetch_token(), field="file",
                filename=name.replace('"', "'").replace("\n", " ").replace("\r", " "),
                content=content, content_type=content_type,
                idempotency_key=str(uuid.uuid4()))
            if not response.get("fileId"):
                raise RuntimeError(f"upload returned no fileId: {response}")
            return {"ok": True, "taskId": task_id, "fileId": response.get("fileId"),
                    "name": response.get("name", name),
                    "sizeBytes": response.get("sizeBytes", len(content)),
                    "sha256": response.get("sha256"),
                    "note": ("Delivered to the task deliverables ledger (task_files); "
                             "it stays downloadable after the worker is gone.")}
        except RuntimeError as failure:
            error = failure
    _enqueue_failed_upload(config, task_id, resolved, name, content_type, len(content))
    return {"ok": False, "error": str(error), "queued": True,
            "note": ("Upload failed after retries and was queued in "
                     ".task-upload-queue.json; call upload_task_file again to "
                     "retry delivery (the queue flushes opportunistically).")}


def tool_download_task_file(arguments: dict[str, Any]) -> dict[str, Any]:
    config = get_config()
    task_id = _safe_uuid(arguments.get("task_id"), "task_id")
    file_id = _safe_uuid(arguments.get("file_id"), "file_id")
    manifest = _http_json("GET", _api_url(config, f"/api/v1/tasks/{task_id}/files"),
                          token=_fetch_token())
    record = next((item for item in manifest if item.get("fileId") == file_id), None)
    if record is None:
        return {"ok": False, "error": f"file {file_id} is not on task {task_id}"}
    if record.get("role") == "INPUT":
        if not config.manager_url:
            raise ValueError("AGENTTEAMS_MANAGER_URL is required for input attachments")
        session_id = record.get("sessionId")
        source_file_id = record.get("sourceFileId")
        if not session_id or not source_file_id:
            return {"ok": False,
                    "error": "input attachment record lacks the session snapshot"}
        url = (config.manager_url + f"/api/v1/conversations/{session_id}"
               + f"/files/{source_file_id}/content")
    else:
        url = _api_url(config, f"/api/v1/tasks/{task_id}/files/{file_id}/content")
    target_dir = os.path.join(config.workspace_dir, "task-files", task_id)
    os.makedirs(target_dir, exist_ok=True)
    target = os.path.join(target_dir, record.get("name") or file_id)
    _http_download(url, token=_fetch_token(), destination=target)
    return {"ok": True, "taskId": task_id, "fileId": file_id,
            "role": record.get("role"), "path": target,
            "note": "Downloaded into the agent workspace; read it with file tools."}
```

`tool_create_task` 扩展：`prompt` 解析之后追加附件声明校验，`input_json["source"]` 赋值块之后追加投影，`queued` 请求之后追加账本登记：

```python
    attachments = arguments.get("attachments") or []
    if not isinstance(attachments, list):
        raise ValueError("attachments must be a list")
    declared: list[dict[str, Any]] = []
    for index, item in enumerate(attachments, start=1):
        if not isinstance(item, dict):
            raise ValueError(f"attachments[{index}] must be an object")
        declared.append({
            "sessionId": _safe_uuid(item.get("sessionId"), f"attachments[{index}].sessionId"),
            "fileId": _safe_uuid(item.get("fileId"), f"attachments[{index}].fileId"),
            "name": _clean_text(item.get("name"), f"attachments[{index}].name", 255,
                                required=True),
            "sizeBytes": _positive_int(item.get("sizeBytes"), f"attachments[{index}].sizeBytes"),
        })
```

```python
    if declared:
        input_json["attachments"] = declared
```

```python
    if declared:
        # 账本登记（task_files role=INPUT）。inputJson.attachments 投影已随任务下发；
        # 登记失败抛错让 agent 感知（任务已建，凭 taskId 可重试，服务端幂等去重）。
        _http_json(
            "POST", _api_url(config, f"/api/v1/tasks/{task_id}/attachments"),
            token=_fetch_token(), idempotency_key=str(uuid.uuid4()),
            body={"attachments": declared},
        )
```

`tool_get_task_result` 扩展（`artifacts` 循环后）：

```python
    task_files = [
        {"fileId": item.get("fileId"), "role": item.get("role"),
         "name": item.get("name"), "contentType": item.get("contentType"),
         "sizeBytes": item.get("sizeBytes"), "status": item.get("status")}
        for item in result.get("taskFiles") or []
    ]
```

返回 dict 追加 `"taskFiles": task_files` 与 note：

```python
    return {"ok": True, "taskId": task_id, "status": result.get("status"),
            "summary": result.get("summary"), "artifacts": artifacts,
            "taskFiles": task_files,
            "note": "artifacts are text/result deliverables from the run manifest; "
                    "taskFiles are input attachments and binary deliverables. "
                    "Use download_task_file to fetch taskFiles into the workspace."}
```

`call_tool` 追加分发（`upload_file` 分支后）：

```python
    if name == "upload_task_file":
        return tool_upload_task_file(arguments)
    if name == "download_task_file":
        return tool_download_task_file(arguments)
```

- [ ] **步骤 4：运行测试验证通过**

运行：`python3 scripts/test_agentteams_task_mcp.py -v`
预期：全绿（含改后 `len(tools) == 9`）。

- [ ] **步骤 5：重生成 ConfigMap 同步副本并验证契约**

运行（块标量重生成；空行不加缩进，yaml 解析后与源文件逐字一致）：

```bash
python3 - <<'PY'
from pathlib import Path
script = Path("scripts/agentteams-task-mcp.py").read_text()
manifest_path = Path("deploy/kind-qwenpaw-task-mcp.yaml")
manifest = manifest_path.read_text()
key = "  agentteams-task-mcp.py: |-\n"
tail_key = "  mcp-env.json: |-\n"
head, _, rest = manifest.partition(key)
_, _, tail = rest.partition(tail_key)
embedded = "".join(
    ("    " + line) if line.strip() else line
    for line in script.splitlines(keepends=True))
manifest_path.write_text(head + key + embedded + "\n" + tail_key + tail)
PY
python3 scripts/test_agentteams_task_mcp.py -k deploy_configmap -v
```

预期：`test_deploy_configmap_embeds_current_script` PASS。

- [ ] **步骤 6：Commit**

```bash
git add scripts/agentteams-task-mcp.py scripts/test_agentteams_task_mcp.py \
        deploy/kind-qwenpaw-task-mcp.yaml
git commit -m "feat(mcp): 任务文件上传/下载工具、附件下发投影与失败重试队列（G05）"
```

---

### 任务 5：runtime promptText 注入附件清单与 OUTPUT 上传引导

**文件：**
- 修改：`runtime/src/main/java/io/agentteams/runtime/QwenPawHttpRuntimePort.java`（`promptText`，约 489-512 行）
- 测试：`runtime/src/test/java/io/agentteams/runtime/QwenPawHttpRuntimePortTest.java`

**前置事实：** `promptText` 是 static 方法，从 `task.inputJson()` 解析 `prompt` 成员；`QwenPawHttpRuntimePortTest` 用 `com.sun.net.httpserver.HttpServer` 捕获请求体（`captureRequest` helper）断言注入文本；`RuntimeTask` 四参构造 `new RuntimeTask(UUID id, String type, String inputJson, Map<String,String> metadata)`。既有断言 `text.endsWith(task.inputJson())` / `endsWith("帮我总结周报")` 依赖「注入块全部在 body 之前」——新块必须继续保持该顺序。

- [ ] **步骤 1：编写失败的测试**

`QwenPawHttpRuntimePortTest` 追加两个用例（沿用该类既有 `server`/`captureRequest`/`writeResponse`/`port()`/`context()` 设施）：

```java
    @Test
    void promptInjectsAttachmentBlockAndUploadGuidance() throws Exception {
        server.createContext("/api/console/chat", exchange -> {
            captureRequest(exchange);
            writeResponse(exchange, 200, "text/event-stream",
                    "data: {\"status\":\"completed\",\"output\":\"ack\"}\n\n");
        });
        server.start();

        QwenPawHttpRuntimePort port = port();
        CountDownLatch completed = new CountDownLatch(1);
        RuntimeTask task = new RuntimeTask(
                UUID.fromString("00000000-0000-0000-0000-0000000000b2"), "chat",
                "{\"prompt\":\"分析这份报告\",\"attachments\":[{"
                        + "\"sessionId\":\"00000000-0000-0000-0000-0000000000b3\","
                        + "\"fileId\":\"00000000-0000-0000-0000-0000000000b4\","
                        + "\"name\":\"报告.pdf\",\"sizeBytes\":12345}]}", Map.of());
        port.start(context(), value -> completed.countDown());
        port.submit(task);

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        JsonNode request = MAPPER.readTree(requestBody.get());
        String text = request.path("input").get(0).path("content").get(0).path("text").asText();
        assertThat(text).contains("[输入附件]");
        assertThat(text).contains(
                "- name=报告.pdf file_id=00000000-0000-0000-0000-0000000000b4"
                        + " session_id=00000000-0000-0000-0000-0000000000b3 size=12345");
        assertThat(text).contains("download_task_file");
        assertThat(text).contains("upload_task_file");
        assertThat(text).endsWith("分析这份报告");
        port.stop();
    }

    @Test
    void promptKeepsUploadGuidanceWithoutAttachments() throws Exception {
        server.createContext("/api/console/chat", exchange -> {
            captureRequest(exchange);
            writeResponse(exchange, 200, "text/event-stream",
                    "data: {\"status\":\"completed\",\"output\":\"ack\"}\n\n");
        });
        server.start();

        QwenPawHttpRuntimePort port = port();
        CountDownLatch completed = new CountDownLatch(1);
        RuntimeTask task = new RuntimeTask(
                UUID.fromString("00000000-0000-0000-0000-0000000000b5"), "chat",
                "{\"prompt\":\"帮我总结周报\"}", Map.of());
        port.start(context(), value -> completed.countDown());
        port.submit(task);

        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        JsonNode request = MAPPER.readTree(requestBody.get());
        String text = request.path("input").get(0).path("content").get(0).path("text").asText();
        assertThat(text).doesNotContain("[输入附件]");
        assertThat(text).contains("upload_task_file");
        assertThat(text).endsWith("帮我总结周报");
        port.stop();
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -pl runtime test -Dtest=QwenPawHttpRuntimePortTest`
预期：两个新用例 FAIL（无 `upload_task_file` 引导、无 `[输入附件]`），既有用例 PASS。

- [ ] **步骤 3：实现 promptText 扩展**

`QwenPawHttpRuntimePort.promptText`（替换整个方法）与新增 `attachmentBlock`：

```java
    private static String promptText(RuntimeTask task) {
        String body;
        JsonNode input = null;
        try {
            input = new ObjectMapper().readTree(task.inputJson());
            if (input.isObject()) {
                JsonNode prompt = input.path("prompt");
                if (prompt.isTextual() && !prompt.asText().isBlank()) {
                    body = prompt.asText();
                } else {
                    body = task.inputJson();
                }
            } else {
                body = task.inputJson();
            }
        } catch (IOException ignored) {
            // Fall through and forward the raw input.
            body = task.inputJson();
        }
        return "[平台上下文]\n"
                + "taskId=" + task.id() + "\n"
                + "（可用 agentteams-task MCP 工具引用此 taskId 登记子任务拆解或汇报子任务状态；"
                + "除这些工具的参数外不要复述本段内容）\n"
                + attachmentBlock(input)
                + "（若你生成了 PDF/图片/Office 等二进制文件，必须调用 upload_task_file 上传到任务交付清单；"
                + "文本产物仍按最终 JSON artifacts 交付）\n\n"
                + body;
    }

    /**
     * G05: input-attachment snapshot block; empty unless the task spec carried
     * attachments (MCP create_task writes them into inputJson, spec §7.1).
     */
    private static String attachmentBlock(JsonNode input) {
        if (input == null || !input.has("attachments") || !input.path("attachments").isArray()
                || input.path("attachments").isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder("[输入附件]\n");
        for (JsonNode item : input.path("attachments")) {
            block.append("- name=").append(item.path("name").asText("file"))
                    .append(" file_id=").append(item.path("fileId").asText())
                    .append(" session_id=").append(item.path("sessionId").asText())
                    .append(" size=").append(item.path("sizeBytes").asLong(0)).append('\n');
        }
        block.append("  （必须先用 download_task_file 下载到工作区再读取；"
                + "除工具参数外不要复述本段）\n");
        return block.toString();
    }
```

- [ ] **步骤 4：运行测试验证通过并 commit**

运行：`mvn -pl runtime test`
预期：全绿（新 2 用例 + 既有零回归；`endsWith` 断言不破坏——注入块全部在 body 之前）。

```bash
git add runtime/src/main/java/io/agentteams/runtime/QwenPawHttpRuntimePort.java \
        runtime/src/test/java/io/agentteams/runtime/QwenPawHttpRuntimePortTest.java
git commit -m "feat(runtime): promptText 注入输入附件清单与 OUTPUT 上传引导（G05）"
```

---

### 任务 6：TaskFileReconciliationJob 对账 + OpenAPI 补录 + 部署资产

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/taskfile/JdbcTaskFileRepository.java`（+`findAvailableOutputs`）
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/taskfile/TaskFileService.java`（+`reconcileBatch`，提取共享循环）
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/taskfile/TaskFileReconciliationJob.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java`（Job @Bean）
- 修改：`control-plane/src/main/resources/application.yml`（对账间隔显式化）
- 修改：`openapi/agentteams-public.yaml`
- 测试：`control-plane/src/test/java/io/agentteams/controlplane/taskfile/TaskFileServiceTest.java`（追加）、`control-plane/src/test/java/io/agentteams/controlplane/taskfile/TaskFileReconciliationJobTest.java`

**前置事实：** `SchedulerLeaseService`（`io.agentteams.controlplane.service`）`run(String leaseName, String owner, Instant now, Duration duration, Supplier<T> work)` 返回 `record Result<T>(boolean leader, T value)`；`TaskAssignmentScheduler.defaultOwner(podName)` 生成 owner；`ArtifactRetentionCleanupJob` 用 `@Scheduled(fixedDelayString = "${agentteams.artifact-retention.cleanup-interval-ms:300000}")`。孤儿对象对账已按计划头部偏离 1 降级为 MISSING 标记 + 数量日志（`ObjectStorage` 无列举原语，不引入 `listObjects`）。

- [ ] **步骤 1：编写失败的测试**

`TaskFileServiceTest` 追加：

```java
    @Test
    void reconcileBatchMarksMissingAcrossTasksAndCounts() {
        TaskFileRecord gone = record(TaskFileRecord.OUTPUT, "gone.pdf", "bb");
        when(repository.findAvailableOutputs(100)).thenReturn(List.of(gone));
        when(storage.exists(gone.storageKey())).thenReturn(false);
        when(repository.markMissing(gone.id(), NOW)).thenReturn(true);

        int marked = service.reconcileBatch(100);

        assertThat(marked).isEqualTo(1);
    }

    @Test
    void reconcileBatchIsNoopWithoutStorage() {
        when(storageProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.reconcileBatch(100)).isZero();
        verify(repository, never()).findAvailableOutputs(anyInt());
    }
```

（import 补 `static org.mockito.ArgumentMatchers.anyInt`。）

`TaskFileReconciliationJobTest`：

```java
package io.agentteams.controlplane.taskfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskFileReconciliationJobTest {
    private final TaskFileService service = mock(TaskFileService.class);
    private final SchedulerLeaseService lease = mock(SchedulerLeaseService.class);
    private TaskFileReconciliationJob job;

    @BeforeEach
    void setUp() {
        job = new TaskFileReconciliationJob(service, lease, Clock.systemUTC(), "owner",
                Duration.ofSeconds(30), 200);
    }

    @Test
    void runOnceMapsLeaderAndMarkedCount() {
        when(lease.run(eq("task-file-reconciliation"), eq("owner"), any(Instant.class),
                any(Duration.class), any(Supplier.class)))
                .thenReturn(new SchedulerLeaseService.Result<>(true, 3));

        TaskFileReconciliationJob.RunResult result = job.runOnce();

        assertThat(result.leader()).isTrue();
        assertThat(result.markedMissing()).isEqualTo(3);
    }

    @Test
    void runOnceReportsNonLeaderWithoutWork() {
        when(lease.run(eq("task-file-reconciliation"), eq("owner"), any(Instant.class),
                any(Duration.class), any(Supplier.class)))
                .thenReturn(new SchedulerLeaseService.Result<>(false, null));

        TaskFileReconciliationJob.RunResult result = job.runOnce();

        assertThat(result.leader()).isFalse();
        assertThat(result.markedMissing()).isZero();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -pl control-plane test -Dtest='TaskFileReconciliationJobTest,TaskFileServiceTest'`
预期：编译失败（类/方法不存在）。

- [ ] **步骤 3：实现 repository 查询与 service 批量对账**

`JdbcTaskFileRepository` 在 `findDedup` 之后追加：

```java
    /** Reconciliation scan: AVAILABLE OUTPUT rows in insertion order, bounded. */
    public List<TaskFileRecord> findAvailableOutputs(int limit) {
        return jdbc.query("""
                SELECT * FROM task_files WHERE role = 'OUTPUT' AND status = 'AVAILABLE'
                ORDER BY created_at, id LIMIT ?
                """, mapper(), limit);
    }
```

`TaskFileService`：把 `reconcile(UUID taskId)` 的循环体提取为共享方法，并新增 `reconcileBatch`（`reconcile` 保留原 `findByTask(taskId, null)` 查询不变，既有测试不受影响）：

```java
    public void reconcile(UUID taskId) {
        List<TaskFileRecord> files = repository.findByTask(taskId, null);
        if (files.isEmpty()) {
            return;
        }
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return;
        }
        Instant now = clock.instant();
        for (TaskFileRecord record : files) {
            markMissingIfGone(storage, record, now);
        }
    }

    /** 全表批处理对账（TaskFileReconciliationJob 用）；返回标记 MISSING 的数量。 */
    public int reconcileBatch(int limit) {
        List<TaskFileRecord> outputs = repository.findAvailableOutputs(limit);
        if (outputs.isEmpty()) {
            return 0;
        }
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return 0;
        }
        Instant now = clock.instant();
        int marked = 0;
        for (TaskFileRecord record : outputs) {
            if (markMissingIfGone(storage, record, now)) {
                marked++;
            }
        }
        return marked;
    }

    /** 探测单条 OUTPUT；真正标记了 MISSING 才返回 true。 */
    private boolean markMissingIfGone(ObjectStorage storage, TaskFileRecord record, Instant now) {
        if (record.isInput() || TaskFileRecord.MISSING.equals(record.status())) {
            return false;
        }
        if (!storage.exists(record.storageKey())) {
            return repository.markMissing(record.id(), now);
        }
        return false;
    }

- [ ] **步骤 4：实现 TaskFileReconciliationJob**

创建 `control-plane/src/main/java/io/agentteams/controlplane/taskfile/TaskFileReconciliationJob.java`：

```java
package io.agentteams.controlplane.taskfile;

import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * G05 交付对账：AVAILABLE 的 OUTPUT 账本行经 storage.exists() 探测，缺失标记
 * MISSING（清单可见，不删记录）。多副本经 SchedulerLeaseService 租约单跑，
 * 仿 ArtifactRetentionCleanupJob（规格 §5.3）。
 */
public final class TaskFileReconciliationJob {

    private final TaskFileService files;
    private final SchedulerLeaseService lease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final int batchSize;

    public TaskFileReconciliationJob(TaskFileService files, SchedulerLeaseService lease,
            Clock clock, String owner, Duration leaseDuration, int batchSize) {
        this.files = Objects.requireNonNull(files, "files");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = owner == null || owner.isBlank() ? "task-file-reconciliation" : owner.trim();
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.leaseDuration = leaseDuration;
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 10000");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agentteams.task-file.reconciliation-interval-ms:300000}")
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = clock.instant();
        SchedulerLeaseService.Result<Integer> result = lease.run("task-file-reconciliation",
                owner, now, leaseDuration, () -> files.reconcileBatch(batchSize));
        return result.leader() ? new RunResult(true, result.value()) : new RunResult(false, 0);
    }

    public record RunResult(boolean leader, int markedMissing) {
    }
}
```

- [ ] **步骤 5：装配 @Bean 与配置**

`ControlPlaneConfiguration` 在 `artifactRetentionCleanupJob` @Bean 之后追加：

```java
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean({TaskFileService.class,
            SchedulerLeaseService.class})
    TaskFileReconciliationJob taskFileReconciliationJob(TaskFileService taskFiles,
            SchedulerLeaseService lease, Clock clock,
            @Value("${POD_NAME:}") String podName,
            @Value("${agentteams.task-file.lease-duration:30s}") java.time.Duration leaseDuration,
            @Value("${agentteams.task-file.reconciliation-batch-size:200}") int batchSize) {
        return new TaskFileReconciliationJob(taskFiles, lease, clock,
                TaskAssignmentScheduler.defaultOwner(podName), leaseDuration, batchSize);
    }
```

（import 补 `io.agentteams.controlplane.taskfile.TaskFileReconciliationJob` 与 `TaskFileService`。）

`application.yml` 的 `agentteams:` 段追加（默认值已内嵌在 `fixedDelayString`，显式化便于运维覆盖）：

```yaml
  task-file:
    reconciliation-interval-ms: 300000
    reconciliation-batch-size: 200
    lease-duration: 30s
```

- [ ] **步骤 6：运行测试验证通过**

运行：`mvn -pl control-plane test -Dtest='TaskFileReconciliationJobTest,TaskFileServiceTest'`
预期：PASS。

- [ ] **步骤 7：OpenAPI 补录**

`openapi/agentteams-public.yaml` 三处追加（沿用该文件紧凑单行 schema 与 `*signedHeaders` 锚风格）：

paths（`/api/v1/tasks/{taskId}/subtasks:` 段之前插入）：

```yaml
  /api/v1/tasks/{taskId}/files:
    parameters: *signedHeaders
    post:
      operationId: uploadTaskFile
      tags: [Tasks]
      parameters:
        - $ref: '#/components/parameters/TaskId'
      requestBody:
        required: true
        content:
          multipart/form-data:
            schema:
              type: object
              required: [file]
              properties:
                file: { type: string, format: binary }
      responses:
        '201':
          description: Task-file ledger record (OUTPUT, server-side SHA-256)
          content:
            application/json:
              schema: { $ref: '#/components/schemas/TaskFile' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '413': { description: File exceeds the 50MB multipart limit }
        '503': { description: Object storage is not enabled }
    get:
      operationId: listTaskFiles
      tags: [Tasks]
      parameters:
        - $ref: '#/components/parameters/TaskId'
        - name: role
          in: query
          schema: { type: string, enum: [INPUT, OUTPUT] }
      responses:
        '200':
          description: Task-file ledger listing
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/TaskFile' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
  /api/v1/tasks/{taskId}/attachments:
    parameters: *signedHeaders
    post:
      operationId: registerTaskAttachments
      tags: [Tasks]
      parameters:
        - $ref: '#/components/parameters/TaskId'
        - $ref: '#/components/parameters/IdempotencyKey'
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/TaskAttachmentsRequest' }
      responses:
        '201':
          description: Registered INPUT snapshots
          content:
            application/json:
              schema:
                type: object
                required: [files]
                properties:
                  files:
                    type: array
                    items: { $ref: '#/components/schemas/TaskFile' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { $ref: '#/components/responses/Unauthorized' }
        '404': { $ref: '#/components/responses/NotFound' }
  /api/v1/tasks/{taskId}/files/{fileId}/content:
    parameters: *signedHeaders
    get:
      operationId: downloadTaskFileContent
      tags: [Tasks]
      description: Cluster-internal streaming download (OUTPUT only; INPUT yields 409).
      parameters:
        - $ref: '#/components/parameters/TaskId'
        - name: fileId
          in: path
          required: true
          schema: { type: string, format: uuid }
      responses:
        '200':
          description: Object byte stream
          content:
            application/octet-stream: { schema: { type: string, format: binary } }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { description: INPUT record (object lives in the conversation domain) }
  /api/v1/tasks/{taskId}/files/{fileId}/download:
    parameters: *signedHeaders
    get:
      operationId: downloadTaskFile
      tags: [Tasks]
      description: Browser-audience 302 presigned GET (presignEndpoint).
      parameters:
        - $ref: '#/components/parameters/TaskId'
        - name: fileId
          in: path
          required: true
          schema: { type: string, format: uuid }
      responses:
        '302':
          description: Redirect to the presigned URL
          headers:
            Location: { schema: { type: string, format: uri } }
        '404': { $ref: '#/components/responses/NotFound' }
```

components/schemas（`TaskResultManifest:` 之前插入两个新 schema，并在 `TaskResultManifest.properties` 的 `artifacts` 之后追加 `taskFiles` 字段）：

```yaml
    TaskFile:
      type: object
      required: [fileId, role, name, sizeBytes, status]
      properties:
        fileId: { type: string, format: uuid }
        role: { type: string, enum: [INPUT, OUTPUT] }
        name: { type: string }
        contentType: { type: string, nullable: true }
        sizeBytes: { type: integer, format: int64, minimum: 0 }
        sha256: { type: string, nullable: true }
        status: { type: string, enum: [AVAILABLE, MISSING] }
        sessionId: { type: string, format: uuid, nullable: true }
        sourceFileId: { type: string, format: uuid, nullable: true }
    TaskAttachmentsRequest:
      type: object
      required: [attachments]
      properties:
        attachments:
          type: array
          maxItems: 20
          items:
            type: object
            required: [sessionId, fileId, name, sizeBytes]
            properties:
              sessionId: { type: string, format: uuid }
              fileId: { type: string, format: uuid }
              name: { type: string }
              sizeBytes: { type: integer, format: int64, minimum: 1 }
```

```yaml
        taskFiles:
          type: array
          description: G05 input attachments and binary deliverables (ledger, not manifest).
          items:
            type: object
            required: [fileId, role, name, sizeBytes, status]
            properties:
              fileId: { type: string, format: uuid }
              role: { type: string, enum: [INPUT, OUTPUT] }
              name: { type: string }
              contentType: { type: string, nullable: true }
              sizeBytes: { type: integer, format: int64, minimum: 0 }
              status: { type: string, enum: [AVAILABLE, MISSING] }
              sessionId: { type: string, format: uuid, nullable: true }
              sourceFileId: { type: string, format: uuid, nullable: true }
```

（注意 `TaskResultManifest` 的 `required` 数组不改——`taskFiles` 对旧 run 兼容缺省。）

- [ ] **步骤 8：部署资产核对与 commit**

- ConfigMap 内嵌脚本副本：任务 4 步骤 5 已同步（契约测试守护），本任务无需重复操作。
- helm：`deploy/helm/agentteams-java/values.yaml` 无 artifact-retention 类配置段先例（对账参数走代码默认值），零改动。
- qwenpaw worker rollout（MCP 新工具激活，kind 验收前执行）：

```bash
kubectl -n agentteams rollout restart deployment/qwenpaw-worker
kubectl -n agentteams rollout status deployment/qwenpaw-worker
```

运行：`mvn -pl control-plane test`

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/taskfile/ \
        control-plane/src/main/java/io/agentteams/controlplane/ControlPlaneConfiguration.java \
        control-plane/src/main/resources/application.yml \
        openapi/agentteams-public.yaml
git commit -m "feat(任务域): G05 task_files 对账 Job（租约调度）+ OpenAPI 补录"
```

---

### 任务 7：kind 确定性验收 + L5 真模型验收 + 全模块回归

**文件：**
- 创建：`scripts/run-kind-task-file-delivery.py`
- 创建：`scripts/run-l5-task-file-delivery.py`

**前置事实：** kind 脚本的基础设施段（`fail`/`run_command`/`kubectl`/`start_port_forward`/`stop_port_forward`/`TokenSource`/`request_json`/`require_environment`/`poll_until`/`create_and_queue_task`/`wait_for_terminal_phase`）**逐字拷贝自 [run-kind-subtask-delegation.py](scripts/run-kind-subtask-delegation.py)**（G03 同款）。注意真实签名：`request_json(url, method, body, token, idempotency_key, allow_error=False) -> tuple[int, object]`（返回元组）；`TokenSource(static_token, keycloak_url).get()`；`create_and_queue_task(base_url, tokens, tenant, project, team, title, prompt, queue=True) -> str`。L5 脚本设施逐字拷贝自 [run-l5-subtask-delegation.py](scripts/run-l5-subtask-delegation.py)（G03 的 L5 变体：G03 kind 设施全量 + `cluster_service_url` ClusterIP 直连 + `KUBECTL = ("sudo", "/usr/local/bin/k3s", "kubectl")`，POLL 超时已放宽 900s），仅 `multipart_body`（返回 `(bytes, boundary)`）从 [run-l5-conversation-file-delivery.py](scripts/run-l5-conversation-file-delivery.py) 逐字拷贝。本任务只写两个脚本与 mock 剧本的新增逻辑。

- [ ] **步骤 1：扩展 qwenpaw conversation mock 的任务文件上传剧本（规格 §7.3：openai-mock 触发 MCP 等价调用链）**

mock 无真实 workspace，因此剧本**直调 control-plane REST multipart**（与 task-mcp 同路，先例：G03 `_perform_rest`；效果等价于 agent 调 upload_task_file）。

`scripts/qwenpaw-conversation-mock.py` 改动：

常量区（`FAIL_FIRST_MARKER` 之后）：

```python
TASK_FILE_MARKER = "TASK_FILE_UPLOAD_PROMPT"
MOCK_PDF_BYTES = b"%PDF-1.4\n%conversation-mock-deliverable\n%%EOF\n"
```

`_perform_get` 之后新增 multipart 直传 helper：

```python
def _rest_upload_ok(config: dict[str, str], task_id: str) -> bool:
    """Mock 无 workspace，直传 multipart 模拟 agent 的 upload_task_file 效果。"""
    boundary = "----conversationmock" + uuid.uuid4().hex
    part = (f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="mock-deliverable.pdf"\r\n'
            f"Content-Type: application/pdf\r\n\r\n").encode()
    body = part + MOCK_PDF_BYTES + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        config["base"] + f"/api/v1/tasks/{task_id}/files", data=body, method="POST",
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Accept": "application/json",
            "Authorization": f"Bearer {_bearer_token(config)}",
            "Idempotency-Key": str(uuid.uuid4()),
        })
    with urllib.request.urlopen(request, timeout=REST_TIMEOUT_SECONDS) as response:
        return 200 <= response.status < 300
```

`aggregation_definitions` 之后新增剧本（结构仿 `decomposition_definitions`：tool.started → 执行 → plugin_call_output → completed 终态）：

```python
def task_file_definitions(task_id: str) -> list[tuple[str, dict[str, Any]]]:
    definitions: list[tuple[str, dict[str, Any]]] = [
        ("tool.started", {"type": "tool.started", "tool": "upload_task_file"})]
    ok = False
    config = control_plane_config()
    if config is not None:
        try:
            ok = _rest_upload_ok(config, task_id)
        except Exception as error:  # noqa: BLE001 - best-effort：降级不中断 SSE 流
            print(f"conversation mock task-file upload failed: {error}",
                  file=sys.stderr, flush=True)
    definitions.append((
        "plugin_call_output",
        {"type": "plugin_call_output", "tool": "upload_task_file",
         "status": "success" if ok else "failed"}))
    definitions.extend(STANDARD_DEFINITIONS)
    return definitions
```

标记检测与分支接入：`delegation_context` 同款文本扫描新增独立函数（不改动既有二元组签名）：

```python
def has_task_file_marker(request: dict[str, Any]) -> bool:
    for item in request.get("input") or []:
        if not isinstance(item, dict):
            continue
        for content in item.get("content") or []:
            text = content.get("text") if isinstance(content, dict) else None
            if isinstance(text, str) and TASK_FILE_MARKER in text:
                return True
    return False
```

在 `do_POST` 的 `task_id, marked = delegation_context(request)` 处（约 470 行）并列调用
`task_file_marked = has_task_file_marker(request)`，沿 `session_events(...)` 传入；
在 509-537 行的 `definitions = None` 分支链**最前部**插入：

```python
                if task_file_marked:
                    definitions = task_file_definitions(delegation_task_id)
                elif ...
```

（`elif ...` 即既有 failure/decomposition/aggregation 分支链，保持不动。）

`scripts/test_qwenpaw_conversation_mock.py` 追加（importlib 加载同款）：

```python
    def test_task_file_marker_triggers_upload_definitions(self):
        request = {"input": [{"role": "user", "content": [
            {"type": "text", "text": "taskId=00000000-0000-0000-0000-0000000000c1 "
                                       + MCP.TASK_FILE_MARKER}]}]}
        self.assertTrue(MCP.has_task_file_marker(request))
        definitions = MCP.task_file_definitions("00000000-0000-0000-0000-0000000000c1")
        names = [name for name, _ in definitions]
        self.assertEqual(names[0], "tool.started")
        self.assertIn("plugin_call_output", names)
        self.assertEqual(names[-1], "message.completed")
        tools = [payload.get("tool") for _, payload in definitions
                 if payload.get("type") in ("tool.started", "plugin_call_output")]
        self.assertEqual(tools, ["upload_task_file", "upload_task_file"])

    def test_has_task_file_marker_false_for_plain_prompt(self):
        request = {"input": [{"role": "user", "content": [
            {"type": "text", "text": "普通任务"}]}]}
        self.assertFalse(MCP.has_task_file_marker(request))
```

重生成 mock ConfigMap 同步副本（`test_qwenpaw_conversation_mock.py:395` 契约守护；用任务 4 步骤 5 同款重生成脚本，manifest 路径与 data 键名以该契约测试为准，即 `deploy/kind-qwenpaw-openai-mock.yaml` 与其内嵌键）：

```bash
python3 scripts/test_qwenpaw_conversation_mock.py -v
```

预期：全绿（含既有 G03 剧本用例零回归）。

```bash
git add scripts/qwenpaw-conversation-mock.py scripts/test_qwenpaw_conversation_mock.py \
        deploy/kind-qwenpaw-openai-mock.yaml
git commit -m "feat(mock): 任务文件上传剧本（marker 直传 control-plane，G05 验收链）"
```

- [ ] **步骤 2：编写 kind 确定性验收脚本**

创建 `scripts/run-kind-task-file-delivery.py`（docstring 说明驱动方式与前置要求，同 G03 风格；import 段照抄 G03）。新增 helper（自包含，不依赖 MCP 脚本）：

```python
POLL_TIMEOUT_SECONDS = 240.0
# 与 scripts/qwenpaw-conversation-mock.py 的 TASK_FILE_MARKER 同值：prompt 携带它时
# mock 剧本会代表 agent 直传一份 mock-deliverable.pdf（MCP 工具调用链等价物）。
TASK_FILE_MARKER = "TASK_FILE_UPLOAD_PROMPT"
PDF_BYTES = b"%PDF-1.4\n%agentteams-g05-acceptance\n%%EOF\n"
OVER_LIMIT_BYTES = b"x" * (50 * 1024 * 1024 + 1)


def sha256_hex(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def check(name: str, ok: bool, detail: str = "") -> None:
    if not ok:
        fail(f"{name} failed: {detail}")
    print(f"  PASS {name}")


class ApiError(RuntimeError):
    def __init__(self, status: int, detail: str):
        super().__init__(f"HTTP {status}: {detail}")
        self.status = status


def multipart_payload(field: str, filename: str, content: bytes,
                      content_type: str) -> tuple[bytes, str]:
    boundary = "----agentteams-g05" + uuid.uuid4().hex
    part = (f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n").encode()
    return part + content + f"\r\n--{boundary}--\r\n".encode(), boundary


def upload_multipart(url: str, token: str, filename: str, content: bytes,
                     content_type: str) -> dict:
    body, boundary = multipart_payload("file", filename, content, content_type)
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Authorization": "Bearer " + token,
        "Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        raise ApiError(error.code, error.read().decode("utf-8", "replace")[:300]) from error


def request_bytes(url: str, token: str) -> bytes:
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        raise ApiError(error.code, error.read().decode("utf-8", "replace")[:300]) from error


def request_location(url: str, token: str) -> str | None:
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    try:
        with opener.open(request, timeout=30) as response:
            return response.headers.get("Location")
    except urllib.error.HTTPError as error:
        if error.code in (301, 302, 303, 307, 308):
            return error.headers.get("Location")
        raise ApiError(error.code, "") from error
```

`main()` 断言链（`check` 基于 G03 `fail()`——失败即 raise 的确定性语义，不是 G02 L5 的软收集；两脚本验收语义不同勿混用）：

```python
def main() -> int:
    namespace = "agentteams"
    require_environment(namespace)
    # 布线照抄 G03 main()（run-kind-subtask-delegation.py:355-391）：argparse
    # （--namespace/--base-url/--control-plane-port/--token/--keycloak-port/--tenant/
    # --project/--team）→ try/finally 结构 → keycloak 与 control-plane port-forward
    # → TokenSource → token_subject → ensure_project_membership（finally 里 restore）。
    # manager 无需 port-forward：本链全部调 control-plane（INPUT 假引用不校验跨域
    # 存在性，规格 §4.1；mock 剧本直传的也是 control-plane）。
    tokens = tokens_from_args(args)  # G03 同款二选一段，见本代码块后括注
    base_url = args.base_url.rstrip("/")
    token = tokens.get()

    # 1) 建任务并排队；prompt 携带 marker → mock 剧本代表 agent 直传一份 OUTPUT
    task_id = create_and_queue_task(base_url, tokens, args.tenant, args.project, args.team,
                                    title="G05 任务文件交付验收",
                                    prompt=f"{TASK_FILE_MARKER} 生成一段说明文字即可。")
    # 2) INPUT 登记（假引用——API 不做跨域存在性校验，best-effort 契约 §4.1）
    request_json(f"{base_url}/api/v1/tasks/{task_id}/attachments", "POST",
                 {"attachments": [{"sessionId": str(uuid.uuid4()),
                                   "fileId": str(uuid.uuid4()),
                                   "name": "输入.txt", "sizeBytes": 12}]},
                 token, str(uuid.uuid4()))
    _, manifest = request_json(f"{base_url}/api/v1/tasks/{task_id}/files", token=token)
    manifest = manifest or []
    check("INPUT 登记入清单", any(f["role"] == "INPUT" for f in manifest))
    input_id = next(f["fileId"] for f in manifest if f["role"] == "INPUT")

    # 3) OUTPUT 上传：服务端直传 + 服务端 SHA-256
    created = upload_multipart(f"{base_url}/api/v1/tasks/{task_id}/files", token,
                               "top10.pdf", PDF_BYTES, "application/pdf")
    check("上传返回 sha256 与本地一致",
          created.get("sha256") == sha256_hex(PDF_BYTES), str(created))
    file_id = created["fileId"]

    # 4) 集群内代理流回读一致
    echoed = request_bytes(f"{base_url}/api/v1/tasks/{task_id}/files/{file_id}/content", token)
    check("content 回读一致", echoed == PDF_BYTES)

    # 5) 幂等重传去重（验收总表第 2 条：不产生失控重复）
    again = upload_multipart(f"{base_url}/api/v1/tasks/{task_id}/files", token,
                             "top10.pdf", PDF_BYTES, "application/pdf")
    check("重传返回既有记录", again.get("fileId") == file_id, str(again))
    _, manifest = request_json(f"{base_url}/api/v1/tasks/{task_id}/files", token=token)
    outputs = [f for f in (manifest or []) if f["role"] == "OUTPUT"]
    check("OUTPUT 无重复（本脚本 1 条 + mock 剧本 1 条）", len(outputs) == 2, str(len(outputs)))

    # 6) 50MB 超限 413；INPUT content 409
    try:
        upload_multipart(f"{base_url}/api/v1/tasks/{task_id}/files", token,
                         "big.bin", OVER_LIMIT_BYTES, "application/octet-stream")
        check("50MB 超限 413", False)
    except ApiError as error:
        check("50MB 超限 413", error.status == 413, str(error))
    try:
        request_bytes(f"{base_url}/api/v1/tasks/{task_id}/files/{input_id}/content", token)
        check("INPUT content 409", False)
    except ApiError as error:
        check("INPUT content 409", error.status == 409, str(error))

    # 7) 浏览器 302 出口（presignEndpoint 受众）
    location = request_location(
        f"{base_url}/api/v1/tasks/{task_id}/files/{file_id}/download", token)
    check("302 Location 是 presigned URL",
          location is not None and ("X-Amz" in location or "presign" in location.lower()),
          str(location))

    # 8) run 终态 + result 聚合 taskFiles（含 mock 剧本直传的那条 → MCP 调用链闭环）
    wait_for_terminal_phase(base_url, tokens, task_id)
    _, runs = request_json(f"{base_url}/api/v1/tasks/{task_id}/runs", token=token)
    if not runs:
        fail("no runs recorded after terminal phase")
    latest = max(runs, key=lambda run: run.get("createdAt", ""))
    _, result = request_json(
        f"{base_url}/api/v1/tasks/{task_id}/runs/{latest['id']}/result", token=token)
    task_files = result.get("taskFiles") or []
    check("result 聚合 taskFiles 含 OUTPUT 且 AVAILABLE",
          any(f["fileId"] == file_id and f["status"] == "AVAILABLE" for f in task_files),
          str(task_files))
    check("mock 剧本直传的 OUTPUT 也在清单",
          any(f["name"] == "mock-deliverable.pdf" for f in task_files), str(task_files))

    # 9) Worker 删除后仍可下载（验收总表第 1 条；kind 验收专用破坏性操作）
    kubectl(namespace, "delete", "workers", "--all")

    def content_survives():
        try:
            return request_bytes(
                f"{base_url}/api/v1/tasks/{task_id}/files/{file_id}/content",
                token) == PDF_BYTES or None
        except ApiError:
            return None  # 轮询窗口内的瞬时错误按未就绪处理

    poll_until(content_survives,
               "content to stay downloadable after workers are deleted")
    print("G05 task-file delivery kind acceptance: PASS")
    return 0
```

（`tokens_from_args(args)` 即 G03 main() 的取 token 段：`if args.token: TokenSource(args.token, "")`，否则 `start_port_forward(keycloak)` + `TokenSource("", f"http://127.0.0.1:{args.keycloak_port}")`；连同 `token_subject`/`ensure_project_membership`/`restore_project_membership`/`database_password`/`sql_literal`/`psql` 逐字拷贝——脚本 subject 需 ADMIN membership 才能过 scope 校验。）

- [ ] **步骤 3：编写 L5 真模型验收脚本（best-effort 层，规格 §7.3/§8.3）**

创建 `scripts/run-l5-task-file-delivery.py`。规格 §8.3 中确定性层全断言归 kind 脚本（步骤 2），L5 只做真模型一轮完整交付；设施逐字拷贝 run-l5-subtask-delegation.py（`fail`/`run_command`/`kubectl`/`cluster_service_url`/`KUBECTL`/`TokenSource`/`request_json`/`token_subject`/`database_password`/`sql_literal`/`psql`/`ensure_project_membership`/`restore_project_membership`/`poll_until`），`multipart_body` 逐字拷贝自 run-l5-conversation-file-delivery.py。新增两个 helper（fail 语义，供真模型轮与回读用）：

```python
def upload_multipart(url: str, token: str, filename: str, content: bytes,
                     content_type: str) -> dict:
    body, boundary = multipart_body("file", filename, content, content_type)
    request = urllib.request.Request(url, data=body, method="POST", headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/json",
        "Content-Type": f"multipart/form-data; boundary={boundary}"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode() or "{}")
    except urllib.error.HTTPError as error:
        fail(f"upload_multipart HTTP {error.code} from {url}: "
             f"{error.read().decode(errors='replace')[:300]}")


def request_bytes(url: str, token: str) -> bytes:
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        fail(f"request_bytes HTTP {error.code} from {url}: "
             f"{error.read().decode(errors='replace')[:300]}")
```

`main()` 骨架（ClusterIP 直连 + membership 前置，同 run-l5-subtask-delegation.py main() 形态）：

```python
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--namespace", default="agentteams")
    parser.add_argument("--base-url", default="")     # control-plane，空则 ClusterIP 解析
    parser.add_argument("--keycloak-url", default="") # 空则 ClusterIP 解析
    parser.add_argument("--manager-url", default="")  # 空则解析 agentteams-agentteams-java-manager
    parser.add_argument("--tenant", default="tenant-a")
    parser.add_argument("--project", default="project-a")
    parser.add_argument("--team", default="team-a")
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--skip-best-effort", action="store_true")
    args = parser.parse_args()

    previous_role: str | None = None
    subject: str | None = None
    try:
        base_url = args.base_url.rstrip("/") or cluster_service_url(
            args.namespace, "agentteams-agentteams-java-control-plane")
        keycloak_url = args.keycloak_url.rstrip("/") or cluster_service_url(
            args.namespace, "keycloak")
        manager_url = args.manager_url.rstrip("/") or cluster_service_url(
            args.namespace, "agentteams-agentteams-java-manager")
        print(f"control-plane={base_url} manager={manager_url}")
        tokens = TokenSource(keycloak_url)
        subject = token_subject(tokens.get())
        print(f"authenticated as subject={subject}")
        previous_role = ensure_project_membership(
            args.namespace, subject, args.tenant, args.project)
        print(f"project membership ensured (previous role: {previous_role!r})")
        if not args.skip_best_effort:
            model_round(base_url, manager_url, tokens, args)
        print("PASS l5-task-file-delivery")
        return 0
    finally:
        if subject:
            try:
                restore_project_membership(args.namespace, subject,
                                           args.tenant, args.project, previous_role)
                print("project membership restored")
            except (RuntimeError, urllib.error.URLError) as error:
                print(f"WARNING: membership restore failed: {error}", file=sys.stderr)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, urllib.error.URLError) as error:
        print(f"L5_TASK_FILE_DELIVERY_FAILED: {error}", file=sys.stderr)
        raise SystemExit(1)
```

真模型轮（规格 §7.3 best-effort 链：会话上传 → 建任务带投影+账本 → 终态 → result 聚合 → 回读）：

```python
MODEL_ROUND_PROMPT = (
    "你收到一份输入附件（见平台上下文的[输入附件]清单）。必须先用 download_task_file "
    "把它下载到工作区读取，然后基于内容生成一份单页 PDF 报告，保存到工作区后"
    "调用 upload_task_file 上传到任务交付清单。")


def model_round(base_url: str, manager_url: str, tokens: TokenSource,
                args: argparse.Namespace) -> None:
    """best-effort 真模型交付轮（规格 §7.3）：默认宽松（WARN），--strict 缺失即 fail。"""
    # 1) 会话域上传输入附件（G02 既有链路，真引用）
    session_id = str(uuid.uuid4())
    status, _ = request_json(
        f"{manager_url}/api/v1/conversations", "POST",
        {"sessionId": session_id, "projectId": args.project, "teamId": args.team,
         "workerId": "qwenpaw"},
        tokens.get(), f"l5-task-file-conv-{session_id}")
    if status != 201:
        fail(f"create conversation expected 201, got {status}")
    content = "关键背景：本季度营收增长 12%。".encode()
    uploaded = upload_multipart(
        f"{manager_url}/api/v1/conversations/{session_id}/files", tokens.get(),
        "背景资料.txt", content, "text/plain")
    if not uploaded.get("fileId"):
        fail(f"conversation upload returned no fileId: {uploaded}")
    # 2) 建任务：inputJson.attachments 下发投影 + attachments 账本登记（MCP create_task 同语义）
    attachments = [{"sessionId": session_id, "fileId": uploaded["fileId"],
                    "name": uploaded.get("name", "背景资料.txt"),
                    "sizeBytes": uploaded.get("sizeBytes", len(content))}]
    body = {
        "title": f"l5-task-file-delivery-{uuid.uuid4()}",
        "description": "Task file delivery true model acceptance (G05)",
        "spec": {
            "scope": {"tenant": args.tenant, "project": args.project, "team": args.team},
            "taskType": "qwenpaw",
            "inputJson": {"prompt": MODEL_ROUND_PROMPT, "attachments": attachments},
            "requiredCapabilities": ["qwenpaw"],
        },
    }
    status, created = request_json(f"{base_url}/api/v1/tasks", "POST", body,
                                   tokens.get(), f"l5-task-file-create-{uuid.uuid4()}")
    if not isinstance(created, dict) or not created.get("id"):
        fail(f"task creation returned no id (HTTP {status})")
    task_id = created["id"]
    print(f"real-model task created: {task_id}")
    status, _ = request_json(f"{base_url}/api/v1/tasks/{task_id}/attachments", "POST",
                             {"attachments": attachments}, tokens.get(),
                             f"l5-task-file-attach-{uuid.uuid4()}")
    if status not in (200, 201):
        fail(f"attachments registration expected 2xx, got {status}")
    status, _ = request_json(f"{base_url}/api/v1/tasks/{task_id}/queue", "POST", {},
                             tokens.get(), f"l5-task-file-queue-{uuid.uuid4()}")
    if status != 200:
        fail(f"queue expected 200, got {status}")
    # 3) 轮询终态（真模型：POLL_TIMEOUT_SECONDS 已放宽 900s，沿用）
    phase = wait_for_terminal_phase(base_url, tokens, task_id)
    if phase != "SUCCEEDED":
        # 「执行成功」与「交付成功」分离（规格 §8.2）：run 失败不直接 fail，
        # 交由 OUTPUT 缺失分支按 --strict 决断。
        print(f"NOTE: real-model run ended in {phase}")
    # 4) result 聚合：OUTPUT 存在 → 逐个回读；缺失 → 宽松 WARN / --strict fail
    _, runs = request_json(f"{base_url}/api/v1/tasks/{task_id}/runs", "GET", None,
                           tokens.get())
    if not runs:
        fail("no runs recorded after terminal phase")
    latest = max(runs, key=lambda run: run.get("createdAt", ""))
    _, result = request_json(
        f"{base_url}/api/v1/tasks/{task_id}/runs/{latest['id']}/result", "GET", None,
        tokens.get())
    outputs = [f for f in (result.get("taskFiles") or [])
               if f.get("role") == "OUTPUT" and f.get("status") == "AVAILABLE"]
    if not outputs:
        message = "真模型未产出任务文件（best-effort 层，prompt 引导未生效）"
        if args.strict:
            fail(message)
        print(f"WARN: {message}")
        return
    for item in outputs:
        echoed = request_bytes(
            f"{base_url}/api/v1/tasks/{task_id}/files/{item['fileId']}/content",
            tokens.get())
        print(f"  PASS 真模型 OUTPUT {item.get('name')} 可下载（{len(echoed)} 字节）")
```

- [ ] **步骤 4：本地运行脚本语法与单测回归**

```bash
python3 -m py_compile scripts/run-kind-task-file-delivery.py scripts/run-l5-task-file-delivery.py
python3 scripts/test_agentteams_task_mcp.py -v
```

预期：py_compile 零输出（语法通过）；unittest 全绿。

- [ ] **步骤 5：全模块回归 + Commit**

```bash
mvn -q test
```

预期：全模块 0 失败（规格 §8.3 验收形态）。

```bash
git add scripts/run-kind-task-file-delivery.py scripts/run-l5-task-file-delivery.py
git commit -m "test(验收): G05 任务文件交付 kind 确定性层与 L5 真模型验收脚本"
```

- [ ] **步骤 6：kind 验收执行（需要 kind 栈在跑；记录结果到任务日志）**

```bash
python3 scripts/run-kind-task-file-delivery.py 2>&1 | tee .local/g05-kind-acceptance.log
```

预期：末行 `G05 task-file delivery kind acceptance: PASS`。若 qwenpaw ConfigMap 变更未 rollout，先执行任务 6 步骤 8 的 rollout restart 再跑。

L5 真模型轮在 L5 主机执行（脚本自解析 ClusterIP；需先把新版 task-mcp 脚本部署进 L5 集群 qwenpaw ConfigMap 并 rollout restart，步骤同任务 4 步骤 5 与任务 6 步骤 8）：

```bash
python3 scripts/run-l5-task-file-delivery.py 2>&1 | tee .local/g05-l5-acceptance.log
```

预期：末行 `PASS l5-task-file-delivery`；若真模型未产出任务文件则出现 `WARN`（--strict 下脚本以 `L5_TASK_FILE_DELIVERY_FAILED` 退出）。L5 执行依赖远端环境可达性，作为验收收尾项在 kind 验收 PASS 后进行。

---

## 验收对照（规格 §8.2 → 本计划断言落点）

| 验收重点 | 断言落点 |
|---|---|
| Worker 删除后仍可下载 | 任务 7 步骤 2 main() 第 9 步（delete workers → content 回读一致） |
| 重试不产生失控重复文件 | 任务 2 `uploadDedupReturnsExistingRecordWithoutNewObject`（单测）+ 任务 7 步骤 2 main() 第 5 步（fileId 不变、OUTPUT 清单数量不变） |
| 「执行成功」与「交付成功」分离 | run 终态不受交付影响（架构公理）+ `taskFiles.status` 可观测（任务 2 result 聚合 + 任务 7 步骤 2 main() 第 8 步；L5 轮 run FAILED 不直接 fail、由 OUTPUT 缺失分支决断） |
| 失败交付可重试且可观测 | 任务 4 `test_upload_task_file_queues_on_failure_and_flushes_on_next_call`（队列落盘→flush 恢复）+ 对账 MISSING 标记（任务 6 `reconcileBatchMarksMissingAcrossTasksAndCounts`） |
| openai-mock 剧本触发 MCP 等价调用链（§7.3） | 任务 7 步骤 1 mock 剧本（marker → `_rest_upload_ok` 直传）+ 任务 7 步骤 2 main() 第 8 步（mock-deliverable.pdf 在 result 清单） |
