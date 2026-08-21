package io.agentteams.controlplane.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TeamRepository {
    private final JdbcTemplate jdbc;

    TeamRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(TeamRecord team) {
        jdbc.update("""
                INSERT INTO teams(id, name, display_name, status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, team.id(), team.name(), team.displayName(), team.status(),
                JdbcSupport.timestamp(team.createdAt()), JdbcSupport.timestamp(team.updatedAt()), team.version());
    }

    public Optional<TeamRecord> findById(UUID id) {
        return jdbc.query("SELECT id, name, display_name, status, created_at, updated_at, version FROM teams WHERE id = ?",
                (rs, row) -> new TeamRecord(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("display_name"), rs.getString("status"), JdbcSupport.instant(rs, "created_at"),
                JdbcSupport.instant(rs, "updated_at"), rs.getLong("version")), id).stream().findFirst();
    }

    public Optional<TeamRecord> findByNameForUpdate(String name) {
        return jdbc.query("""
                SELECT id, name, display_name, status, created_at, updated_at, version
                  FROM teams WHERE name = ? FOR UPDATE
                """, (rs, row) -> new TeamRecord(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("display_name"), rs.getString("status"), JdbcSupport.instant(rs, "created_at"),
                        JdbcSupport.instant(rs, "updated_at"), rs.getLong("version")), name).stream().findFirst();
    }

    public void upsert(TeamRecord team) {
        jdbc.update("""
                INSERT INTO teams(id, name, display_name, status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET id = EXCLUDED.id, display_name = EXCLUDED.display_name,
                    status = EXCLUDED.status, updated_at = EXCLUDED.updated_at,
                    version = teams.version + 1
                """, team.id(), team.name(), team.displayName(), team.status(), JdbcSupport.timestamp(team.createdAt()),
                JdbcSupport.timestamp(team.updatedAt()), team.version());
    }

    public void insertPolicy(TeamPolicyRecord policy) {
        jdbc.update("""
                INSERT INTO team_policies(team_id, max_concurrent_tasks, require_human_approval,
                    allowed_runtimes, required_capabilities, updated_at, version)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, policy.teamId(), policy.maxConcurrentTasks(), policy.requireHumanApproval(),
                JdbcSupport.jsonArray(policy.allowedRuntimes()), JdbcSupport.jsonArray(policy.requiredCapabilities()),
                JdbcSupport.timestamp(policy.updatedAt()), policy.version());
    }

    public Optional<TeamPolicyRecord> findPolicy(UUID teamId) {
        return jdbc.query("""
                SELECT team_id, max_concurrent_tasks, require_human_approval, allowed_runtimes::text,
                       required_capabilities::text, updated_at, version
                  FROM team_policies WHERE team_id = ?
                """, (rs, row) -> new TeamPolicyRecord(rs.getObject("team_id", UUID.class),
                        rs.getInt("max_concurrent_tasks"), rs.getBoolean("require_human_approval"),
                        JdbcSupport.stringArray(rs.getString("allowed_runtimes")),
                        JdbcSupport.stringArray(rs.getString("required_capabilities")),
                        JdbcSupport.instant(rs, "updated_at"), rs.getLong("version")), teamId).stream().findFirst();
    }

    public void upsertPolicy(TeamPolicyRecord policy) {
        jdbc.update("""
                INSERT INTO team_policies(team_id, max_concurrent_tasks, require_human_approval,
                    allowed_runtimes, required_capabilities, updated_at, version)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                ON CONFLICT (team_id) DO UPDATE SET max_concurrent_tasks = EXCLUDED.max_concurrent_tasks,
                    require_human_approval = EXCLUDED.require_human_approval,
                    allowed_runtimes = EXCLUDED.allowed_runtimes,
                    required_capabilities = EXCLUDED.required_capabilities,
                    updated_at = EXCLUDED.updated_at, version = team_policies.version + 1
                """, policy.teamId(), policy.maxConcurrentTasks(), policy.requireHumanApproval(),
                JdbcSupport.jsonArray(policy.allowedRuntimes()), JdbcSupport.jsonArray(policy.requiredCapabilities()),
                JdbcSupport.timestamp(policy.updatedAt()), policy.version());
    }

    public void insertMember(TeamMemberRecord member) {
        jdbc.update("""
                INSERT INTO team_memberships(id, team_id, agent_id, role, status, joined_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, member.id(), member.teamId(), member.agentId(), member.role(), member.status(),
                JdbcSupport.timestamp(member.joinedAt()), JdbcSupport.timestamp(member.updatedAt()), member.version());
    }

    public List<TeamMemberRecord> activeMembers(UUID teamId) {
        return jdbc.query("""
                SELECT id, team_id, agent_id, role, status, joined_at, updated_at, version
                  FROM team_memberships WHERE team_id = ? AND status = 'ACTIVE' ORDER BY id
                """, (rs, row) -> mapMember(rs), teamId);
    }

    public List<TeamMemberRecord> allMembers(UUID teamId) {
        return jdbc.query("""
                SELECT id, team_id, agent_id, role, status, joined_at, updated_at, version
                  FROM team_memberships WHERE team_id = ? ORDER BY joined_at, id
                """, (rs, row) -> mapMember(rs), teamId);
    }

    public void replaceActiveMembers(UUID teamId, List<TeamMemberRecord> members, java.time.Instant now) {
        jdbc.update("""
                UPDATE team_memberships SET status = 'INACTIVE', updated_at = ?, version = version + 1
                 WHERE team_id = ? AND status = 'ACTIVE'
                """, JdbcSupport.timestamp(now), teamId);
        for (TeamMemberRecord member : members) {
            jdbc.update("""
                    INSERT INTO team_memberships(id, team_id, agent_id, role, status, joined_at, updated_at, version)
                    VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                    ON CONFLICT (team_id, agent_id) DO UPDATE SET role = EXCLUDED.role,
                        status = 'ACTIVE', updated_at = EXCLUDED.updated_at,
                        version = team_memberships.version + 1
                    """, member.id(), member.teamId(), member.agentId(), member.role(),
                    JdbcSupport.timestamp(member.joinedAt()), JdbcSupport.timestamp(member.updatedAt()), member.version());
        }
    }

    public Optional<TeamMemberRecord> findActiveMember(UUID teamId, UUID agentId) {
        return jdbc.query("""
                SELECT id, team_id, agent_id, role, status, joined_at, updated_at, version
                  FROM team_memberships WHERE team_id = ? AND agent_id = ? AND status = 'ACTIVE'
                """, (rs, row) -> mapMember(rs), teamId, agentId).stream().findFirst();
    }

    public int activeAssignmentCount(UUID teamId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM team_task_assignments
                 WHERE team_id = ? AND released_at IS NULL AND status IN ('ASSIGNED', 'RUNNING')
                """, Integer.class, teamId);
        return count == null ? 0 : count;
    }

    public void linkTask(UUID teamId, UUID taskId, String approvalStatus, java.time.Instant now) {
        jdbc.update("""
                INSERT INTO team_tasks(team_id, task_id, approval_status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 0)
                ON CONFLICT (team_id, task_id) DO UPDATE SET approval_status = EXCLUDED.approval_status,
                    updated_at = EXCLUDED.updated_at, version = team_tasks.version + 1
                """, teamId, taskId, approvalStatus, JdbcSupport.timestamp(now), JdbcSupport.timestamp(now));
    }

    public void insertTaskAssignment(UUID id, UUID teamId, UUID taskId, UUID agentId, UUID membershipId,
            String status, java.time.Instant assignedAt) {
        jdbc.update("""
                INSERT INTO team_task_assignments(id, team_id, task_id, agent_id, membership_id, status, assigned_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """, id, teamId, taskId, agentId, membershipId, status, JdbcSupport.timestamp(assignedAt));
    }

    public void releaseTaskAssignment(UUID teamId, UUID taskId, java.time.Instant releasedAt) {
        jdbc.update("""
                UPDATE team_task_assignments SET status = 'RELEASED', released_at = ?, version = version + 1
                 WHERE team_id = ? AND task_id = ? AND released_at IS NULL
                """, JdbcSupport.timestamp(releasedAt), teamId, taskId);
    }

    public void markDeleted(UUID teamId, java.time.Instant now) {
        jdbc.update("""
                UPDATE teams SET status = 'DELETED', updated_at = ?, version = version + 1 WHERE id = ?
                """, JdbcSupport.timestamp(now), teamId);
        jdbc.update("""
                UPDATE team_memberships SET status = 'INACTIVE', updated_at = ?, version = version + 1
                 WHERE team_id = ? AND status = 'ACTIVE'
                """, JdbcSupport.timestamp(now), teamId);
    }

    private TeamMemberRecord mapMember(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TeamMemberRecord(rs.getObject("id", UUID.class), rs.getObject("team_id", UUID.class),
                rs.getObject("agent_id", UUID.class), rs.getString("role"), rs.getString("status"),
                JdbcSupport.instant(rs, "joined_at"), JdbcSupport.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
