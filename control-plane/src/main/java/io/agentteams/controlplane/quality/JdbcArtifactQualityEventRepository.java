package io.agentteams.controlplane.quality;

import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcArtifactQualityEventRepository implements ArtifactQualityEventRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcArtifactQualityEventRepository(DataSource source) { this(new JdbcTemplate(source)); }

    public JdbcArtifactQualityEventRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean append(ExecutionContext context, ArtifactQualityEvent event) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(event, "event");
        UUID projectId = UUID.fromString(context.projectId());
        if (!context.organizationId().equals(event.organizationId())
                || !context.tenantId().equals(event.tenantId()) || !projectId.equals(event.projectId())) {
            throw new IllegalArgumentException("event scope must match trusted execution context");
        }
        return jdbc.update("""
                INSERT INTO artifact_quality_events
                    (id, organization_id, tenant_id, project_id, task_id, run_id, sequence, event_type,
                     artifact_name, file_id, manifest_ref, sha256, category_code, check_item, action_source, status,
                     score, score_delta, summary, source_type, source_ref, confidence, idempotency_key,
                     occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (organization_id, tenant_id, project_id, idempotency_key) DO NOTHING
                """, event.id(), context.organizationId(), context.tenantId(), projectId, event.taskId(),
                event.runId(), event.sequence(), event.eventType(), event.artifactName(), event.fileId(),
                event.manifestRef(), event.sha256(), event.categoryCode(), event.checkItem(), event.actionSource(), event.status(),
                event.score(), event.scoreDelta(), event.summary(), event.sourceType(), event.sourceRef(),
                event.confidence(), event.idempotencyKey(), JdbcSupport.timestamp(event.occurredAt())) == 1;
    }

    @Override
    public ArtifactQualityEventFilter.Page find(ExecutionContext context, ArtifactQualityEventFilter filter) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(filter, "filter");
        UUID canonicalProject = UUID.fromString(context.projectId());
        if (filter.projectId() != null && !filter.projectId().equals(canonicalProject)) {
            throw new IllegalArgumentException("filter project must match authorized execution context");
        }
        StringBuilder where = new StringBuilder(" WHERE organization_id=? AND tenant_id=? AND project_id=?");
        List<Object> args = new ArrayList<>(List.of(context.organizationId(), context.tenantId(), canonicalProject));
        if (filter.taskId() != null) add(where, args, " AND task_id=?", filter.taskId());
        if (filter.artifactName() != null) add(where, args, " AND artifact_name=?", filter.artifactName());
        if (filter.status() != null) add(where, args, " AND status=?", filter.status().toUpperCase(java.util.Locale.ROOT));
        if (filter.categoryCode() != null) add(where, args, " AND category_code=?", filter.categoryCode());
        if (filter.from() != null) add(where, args, " AND occurred_at>=?", JdbcSupport.timestamp(filter.from()));
        if (filter.to() != null) add(where, args, " AND occurred_at<=?", JdbcSupport.timestamp(filter.to()));
        long total = jdbc.queryForObject("SELECT count(*) FROM artifact_quality_events" + where, Long.class,
                args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(filter.pageSize());
        pageArgs.add((long) filter.page() * filter.pageSize());
        List<ArtifactQualityEvent> events = jdbc.query("""
                SELECT id, organization_id, tenant_id, project_id, task_id, run_id, sequence, event_type,
                       artifact_name, file_id, manifest_ref, sha256, category_code, check_item, action_source, status,
                       score, score_delta, summary, source_type, source_ref, confidence, idempotency_key, occurred_at
                  FROM artifact_quality_events
                """ + where + " ORDER BY occurred_at, sequence, id LIMIT ? OFFSET ?", this::map, pageArgs.toArray());
        return new ArtifactQualityEventFilter.Page(events, total, filter.page(), filter.pageSize());
    }

    private static void add(StringBuilder where, List<Object> args, String clause, Object value) {
        where.append(clause);
        args.add(value);
    }

    private ArtifactQualityEvent map(ResultSet rs, int row) throws SQLException {
        return new ArtifactQualityEvent(rs.getObject("id", UUID.class), rs.getString("organization_id"),
                rs.getString("tenant_id"), rs.getObject("project_id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getLong("sequence"), rs.getString("event_type"),
                rs.getString("artifact_name"), rs.getObject("file_id", UUID.class), rs.getString("manifest_ref"),
                rs.getString("sha256"), rs.getString("category_code"), rs.getString("check_item"),
                rs.getString("action_source"), rs.getString("status"),
                nullableDouble(rs, "score"), nullableDouble(rs, "score_delta"), rs.getString("summary"),
                rs.getString("source_type"), rs.getString("source_ref"), rs.getString("confidence"),
                rs.getString("idempotency_key"), JdbcSupport.instant(rs, "occurred_at"));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
