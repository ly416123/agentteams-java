package io.agentteams.controlplane.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.security.ExecutionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Optional, offline historical JSONL importer. It runs only when an operator supplies an input path. */
@Component
@ConditionalOnProperty(prefix = "agentteams.artifact-quality.history-import", name = "path")
public final class ArtifactQualityHistoryImportRunner implements CommandLineRunner {
    private static final Logger LOG = Logger.getLogger(ArtifactQualityHistoryImportRunner.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SOURCE_REF = Pattern.compile(
            "history:sha256:[a-f0-9]{64}#(?:case:[A-Za-z0-9_.:-]{1,100}(?::assertion:[0-9]{1,8}|:final-status)?|plan-line:[0-9]{1,8})");
    private final JdbcTemplate jdbc;
    private final ArtifactQualityEventRepository repository;
    private final String path;

    public ArtifactQualityHistoryImportRunner(JdbcTemplate jdbc, ArtifactQualityEventRepository repository,
            @org.springframework.beans.factory.annotation.Value("${agentteams.artifact-quality.history-import.path}") String path) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.path = path;
    }

    @Override
    public void run(String... args) {
        try {
            ImportSummary result = importFile(Path.of(path));
            LOG.log(Level.INFO, "Artifact quality history import complete imported={0} duplicates={1} skipped={2}",
                    new Object[] {result.imported(), result.duplicates(), result.skipped()});
        } catch (RuntimeException | IOException failure) {
            LOG.log(Level.WARNING, "Artifact quality history import failed type={0}", failure.getClass().getSimpleName());
        }
    }

    public ImportSummary importFile(Path input) throws IOException {
        AtomicInteger imported = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        try (var lines = Files.lines(input, java.nio.charset.StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                try {
                    if (line.isBlank()) return;
                    if (importLine(line)) imported.incrementAndGet(); else duplicates.incrementAndGet();
                } catch (RuntimeException failure) {
                    skipped.incrementAndGet();
                }
            });
        }
        return new ImportSummary(imported.get(), duplicates.get(), skipped.get());
    }

    private boolean importLine(String line) {
        JsonNode root;
        try {
            root = JSON.readTree(line);
        } catch (IOException invalidJson) {
            throw new IllegalArgumentException("invalid history record");
        }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("history record must be an object");
        JsonNode scopeNode = root.path("scope");
        String externalTenant = requiredText(scopeNode, "tenant");
        String externalProject = requiredText(scopeNode, "project");
        Scope resolved = resolveScope(externalTenant, externalProject);
        ExecutionContext context = new ExecutionContext(resolved.organizationId(), resolved.tenantId(),
                resolved.projectId().toString(), "history-import", "history-import");
        UUID taskId = uuidOrNull(root.path("taskId"));
        UUID runId = uuidOrNull(root.path("runId"));
        if ((taskId == null) != (runId == null)) throw new IllegalArgumentException("task and run references must be paired");
        String confidence = requiredText(root, "confidence");
        if (taskId != null && !executionBelongsTo(context, taskId, runId)) {
            if (executionExists(taskId, runId)) {
                throw new IllegalArgumentException("historical execution does not belong to the resolved project");
            }
            taskId = null;
            runId = null;
            confidence = "LOW";
        }
        String sourceType = requiredText(root, "sourceType");
        String sourceRef = requiredText(root, "sourceRef");
        if (!"HISTORICAL".equalsIgnoreCase(sourceType) || !SOURCE_REF.matcher(sourceRef).matches()) {
            throw new IllegalArgumentException("historical source reference is invalid");
        }
        ArtifactQualityEvent event = new ArtifactQualityEvent(requiredUuid(root, "id"), resolved.organizationId(),
                resolved.tenantId(), resolved.projectId(), taskId, runId, requiredLong(root, "sequence"),
                requiredText(root, "eventType"), nullableText(root, "artifactName"), uuidOrNull(root.path("fileId")),
                nullableText(root, "manifestRef"), nullableText(root, "sha256"), nullableText(root, "categoryCode"),
                nullableText(root, "checkItem"), nullableText(root, "actionSource"), requiredText(root, "status"),
                nullableDouble(root, "score"), nullableDouble(root, "scoreDelta"), requiredText(root, "summary"),
                "HISTORICAL", sourceRef, confidence, requiredText(root, "idempotencyKey"),
                java.time.Instant.parse(requiredText(root, "occurredAt")));
        return repository.append(context, event);
    }

    private Scope resolveScope(String externalTenant, String externalProject) {
        var values = jdbc.query("""
                SELECT DISTINCT organization.id::text AS organization_id, tenant.id::text AS tenant_id,
                       project.id AS project_id
                  FROM projects project
                  JOIN tenants tenant ON tenant.external_key = project.tenant_id
                  JOIN organizations organization ON organization.id = tenant.organization_id
                  LEFT JOIN legacy_tenant_mappings legacy ON legacy.tenant_id = tenant.id
                 WHERE organization.status = 'ACTIVE' AND tenant.status = 'ACTIVE' AND project.status = 'ACTIVE'
                   AND (? IN (tenant.external_key, tenant.id::text)
                        OR ? = legacy.legacy_tenant_key)
                   AND (? = project.name OR ? = project.id::text)
                """, (rs, row) -> new Scope(rs.getString("organization_id"), rs.getString("tenant_id"),
                rs.getObject("project_id", UUID.class)), externalTenant, externalTenant, externalProject, externalProject);
        if (values.size() != 1) throw new IllegalArgumentException("historical project scope is unavailable or ambiguous");
        return values.get(0);
    }

    private boolean executionBelongsTo(ExecutionContext context, UUID taskId, UUID runId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM task_runs run
                    JOIN tenants tenant ON tenant.id::text = run.tenant_id
                    JOIN projects project ON project.id = ? AND project.tenant_id = tenant.external_key
                    JOIN resource_scopes scope ON scope.resource_type = 'TASK' AND scope.resource_id = run.task_id
                      AND scope.tenant_id = project.tenant_id AND scope.project_id IN (project.id::text, project.name)
                   WHERE run.id = ? AND run.task_id = ? AND run.organization_id = ? AND run.tenant_id = ?)
                """, Boolean.class, UUID.fromString(context.projectId()), runId, taskId,
                context.organizationId(), context.tenantId());
        return Boolean.TRUE.equals(exists);
    }

    private boolean executionExists(UUID taskId, UUID runId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM task_runs WHERE id = ? OR task_id = ?)
                """, Boolean.class, runId, taskId);
        return Boolean.TRUE.equals(exists);
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.asText().trim();
    }

    private static String nullableText(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private static UUID requiredUuid(JsonNode object, String field) {
        UUID value = uuidOrNull(object.path(field));
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static UUID uuidOrNull(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("UUID reference is invalid");
        try { return UUID.fromString(value.asText()); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("UUID reference is invalid"); }
    }

    private static long requiredLong(JsonNode object, String field) {
        if (!object.path(field).canConvertToLong()) throw new IllegalArgumentException(field + " is invalid");
        return object.path(field).asLong();
    }

    private static Double nullableDouble(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isNumber()) throw new IllegalArgumentException(field + " is invalid");
        return value.asDouble();
    }

    public record ImportSummary(int imported, int duplicates, int skipped) { }
    private record Scope(String organizationId, String tenantId, UUID projectId) { }
}
