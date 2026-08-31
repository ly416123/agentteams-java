package io.agentteams.controlplane.skill;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL store for organization-scoped, digest-pinned Skill bindings. */
@Repository
public class JdbcSkillBindingRepository implements SkillBindingRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcSkillBindingRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcSkillBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public SkillBindingRecord bind(SkillBindingRecord record) {
        jdbc.update("""
                INSERT INTO skill_bindings
                    (id, organization_id, tenant_id, project_id, team_id, skill_id, skill_version_id,
                     digest, created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, tenant_id, skill_id, skill_version_id, digest) DO NOTHING
                """, record.id(), record.organizationId(), record.tenantId(), record.projectId(), record.teamId(),
                record.skillId(), record.skillVersionId(), record.digest(), JdbcSupport.timestamp(record.createdAt()),
                record.createdBy());
        return findOne(record.organizationId(), record.tenantId(), record.projectId(), record.teamId(), record.skillId(),
                record.skillVersionId(), record.digest());
    }

    @Override
    public List<SkillBindingRecord> find(String organizationId, String tenantId, String projectId, String teamId) {
        return jdbc.query(select() + " WHERE organization_id = ? AND tenant_id = ? AND project_id = ? AND team_id = ?"
                        + " ORDER BY created_at, id", this::map, organizationId, tenantId, projectId, teamId);
    }

    private SkillBindingRecord findOne(String organizationId, String tenantId, String projectId, String teamId,
            UUID skillId, UUID versionId, String digest) {
        return jdbc.query(select() + " WHERE organization_id = ? AND tenant_id = ? AND project_id = ? AND team_id = ?"
                        + " AND skill_id = ? AND skill_version_id = ? AND digest = ?", this::map,
                organizationId, tenantId, projectId, teamId, skillId, versionId, digest).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("skill binding was not persisted"));
    }

    private SkillBindingRecord map(ResultSet rs, int row) throws SQLException {
        return new SkillBindingRecord(rs.getObject("id", UUID.class), rs.getString("organization_id"),
                rs.getString("tenant_id"), rs.getString("project_id"), rs.getString("team_id"),
                rs.getObject("skill_id", UUID.class), rs.getObject("skill_version_id", UUID.class),
                rs.getString("digest"), JdbcSupport.instant(rs, "created_at"), rs.getString("created_by"));
    }

    private static String select() {
        return """
                SELECT id, organization_id, tenant_id, project_id, team_id, skill_id, skill_version_id,
                       digest, created_at, created_by
                  FROM skill_bindings
                """;
    }
}
