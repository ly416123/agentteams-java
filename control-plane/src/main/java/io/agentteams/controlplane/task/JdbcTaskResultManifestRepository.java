package io.agentteams.controlplane.task;

import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskResultManifest;
import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL result manifest projection; only metadata and object references are stored here. */
@Repository
public class JdbcTaskResultManifestRepository implements TaskResultManifestRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcTaskResultManifestRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcTaskResultManifestRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void upsert(ExecutionContext context, TaskResultManifest manifest) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(manifest, "manifest");
        UUID manifestId = manifestId(manifest.runId());
        java.time.Instant now = java.time.Instant.now();
        jdbc.update("""
                INSERT INTO task_result_manifests
                    (id, task_id, run_id, status, summary, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT (run_id) DO UPDATE SET
                    task_id = EXCLUDED.task_id, status = EXCLUDED.status, summary = EXCLUDED.summary,
                    updated_at = EXCLUDED.updated_at, version = task_result_manifests.version + 1
                """, manifestId, manifest.taskId(), manifest.runId(), manifest.status(), manifest.summary(),
                JdbcSupport.timestamp(now), JdbcSupport.timestamp(now));
        jdbc.update("DELETE FROM task_result_artifacts WHERE manifest_id = ?", manifestId);
        for (TaskResultManifest.ArtifactMetadata artifact : manifest.artifacts()) {
            jdbc.update("""
                    INSERT INTO task_result_artifacts
                        (id, manifest_id, artifact_name, storage_ref, content_type, size_bytes,
                         sha256, version, stage, visibility, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, artifactId(manifestId, artifact.name()), manifestId, artifact.name(), artifact.storageRef(),
                    artifact.contentType(), artifact.sizeBytes(), artifact.sha256(), artifact.version(), artifact.stage(),
                    artifact.visibility().name(), JdbcSupport.timestamp(now));
        }
    }

    @Override
    public Optional<TaskResultManifest> find(ExecutionContext context, UUID taskId, UUID runId,
            Set<TaskEventVisibility> visible) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(visible, "visible");
        if (visible.isEmpty()) return Optional.empty();
        List<TaskResultManifest> manifests = jdbc.query("""
                SELECT manifest.id, manifest.task_id, manifest.run_id, manifest.status, manifest.summary
                  FROM task_result_manifests manifest
                  JOIN task_runs run ON run.id = manifest.run_id
                 WHERE run.organization_id = ? AND run.tenant_id = ?
                   AND manifest.task_id = ? AND manifest.run_id = ?
                """, (rs, row) -> mapManifest(rs), context.organizationId(), context.tenantId(), taskId, runId);
        if (manifests.isEmpty()) return Optional.empty();
        UUID manifestId = manifestId(runId);
        List<TaskResultManifest.ArtifactMetadata> artifacts = findArtifacts(manifestId, visible);
        TaskResultManifest manifest = manifests.get(0);
        return Optional.of(new TaskResultManifest(manifest.taskId(), manifest.runId(), manifest.status(),
                manifest.summary(), artifacts));
    }

    private List<TaskResultManifest.ArtifactMetadata> findArtifacts(UUID manifestId,
            Set<TaskEventVisibility> visible) {
        List<TaskEventVisibility> levels = visible.stream().sorted().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(levels.size(), "?"));
        String sql = """
                SELECT artifact_name, storage_ref, content_type, size_bytes, sha256, version, stage, visibility
                  FROM task_result_artifacts
                 WHERE manifest_id = ? AND visibility IN (""" + placeholders + ") ORDER BY artifact_name";
        List<Object> arguments = new ArrayList<>();
        arguments.add(manifestId);
        levels.forEach(level -> arguments.add(level.name()));
        return jdbc.query(sql, (rs, row) -> new TaskResultManifest.ArtifactMetadata(
                rs.getString("artifact_name"), rs.getString("storage_ref"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("sha256"), rs.getLong("version"), rs.getString("stage"),
                TaskEventVisibility.from(rs.getString("visibility"))), arguments.toArray());
    }

    private TaskResultManifest mapManifest(ResultSet rs) throws SQLException {
        return new TaskResultManifest(rs.getObject("task_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getString("status"), rs.getString("summary"), List.of());
    }

    private static UUID manifestId(UUID runId) {
        return UUID.nameUUIDFromBytes(("task-result-manifest:" + runId).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID artifactId(UUID manifestId, String name) {
        return UUID.nameUUIDFromBytes((manifestId + ":" + name).getBytes(StandardCharsets.UTF_8));
    }
}
