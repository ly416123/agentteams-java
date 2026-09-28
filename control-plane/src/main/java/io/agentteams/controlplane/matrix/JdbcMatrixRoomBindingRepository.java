package io.agentteams.controlplane.matrix;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC persistence for the authoritative Matrix room binding. */
public final class JdbcMatrixRoomBindingRepository implements MatrixRoomBindingRepository {
    private final JdbcTemplate jdbc;

    public JdbcMatrixRoomBindingRepository(javax.sql.DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    JdbcMatrixRoomBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<MatrixRoomBinding> findActiveByRoom(String tenantId, String roomId) {
        return query("""
                SELECT id, organization_id, tenant_id, room_id, primary_context_type, primary_context_id,
                       project_id, team_id, status, created_by, created_at, updated_at, version
                  FROM matrix_room_bindings
                 WHERE tenant_id = ? AND room_id = ? AND status = 'ACTIVE'
                 ORDER BY version DESC
                 LIMIT 1
                """, tenantId, roomId);
    }

    @Override
    public Optional<MatrixRoomBinding> findActiveByContext(String tenantId, MatrixRoomContextType contextType,
            UUID contextId) {
        return query("""
                SELECT id, organization_id, tenant_id, room_id, primary_context_type, primary_context_id,
                       project_id, team_id, status, created_by, created_at, updated_at, version
                  FROM matrix_room_bindings
                 WHERE tenant_id = ? AND primary_context_type = ? AND primary_context_id = ?
                   AND status = 'ACTIVE'
                 ORDER BY version DESC
                 LIMIT 1
                """, tenantId, contextType.name(), contextId);
    }

    @Override
    public MatrixRoomBinding save(MatrixRoomBinding binding) {
        jdbc.update("""
                INSERT INTO matrix_room_bindings
                    (id, organization_id, tenant_id, room_id, primary_context_type, primary_context_id,
                     project_id, team_id, conversation_id, task_id, status, created_by, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, binding.id(), binding.organizationId(), binding.tenantId(), binding.roomId(),
                binding.primaryContextType().name(), binding.primaryContextId(), binding.projectId(), binding.teamId(),
                binding.primaryContextType() == MatrixRoomContextType.CONVERSATION ? binding.primaryContextId() : null,
                binding.primaryContextType() == MatrixRoomContextType.TASK ? binding.primaryContextId() : null,
                binding.status().name(), binding.createdBy(), JdbcSupport.timestamp(binding.createdAt()),
                JdbcSupport.timestamp(binding.updatedAt()), binding.version());
        return binding;
    }

    private Optional<MatrixRoomBinding> query(String sql, Object... arguments) {
        List<MatrixRoomBinding> bindings = jdbc.query(sql, this::map, arguments);
        return bindings.stream().findFirst();
    }

    private MatrixRoomBinding map(ResultSet rs, int row) throws SQLException {
        return new MatrixRoomBinding(rs.getObject("id", UUID.class), rs.getString("organization_id"),
                rs.getString("tenant_id"), rs.getString("room_id"),
                MatrixRoomContextType.valueOf(rs.getString("primary_context_type")),
                rs.getObject("primary_context_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("team_id", UUID.class), MatrixRoomBindingStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }
}
