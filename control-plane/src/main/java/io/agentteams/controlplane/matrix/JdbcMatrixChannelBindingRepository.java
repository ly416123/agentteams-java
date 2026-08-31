package io.agentteams.controlplane.matrix;

import io.agentteams.controlplane.persistence.JdbcSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads Matrix channel bindings without exposing storage details to Channel adapters. */
public final class JdbcMatrixChannelBindingRepository implements MatrixChannelBindingRepository {
    private final JdbcTemplate jdbc;

    public JdbcMatrixChannelBindingRepository(javax.sql.DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    JdbcMatrixChannelBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<MatrixChannelBinding> findById(UUID id) {
        if (id == null) return Optional.empty();
        List<MatrixChannelBinding> bindings = jdbc.query("""
                SELECT id, organization_id, tenant_id, project_id, room_id, event_types::text, enabled
                  FROM matrix_channel_bindings
                 WHERE id = ?
                """, this::map, id);
        return bindings.stream().findFirst();
    }

    private MatrixChannelBinding map(ResultSet rs, int row) throws SQLException {
        return new MatrixChannelBinding(rs.getObject("id", UUID.class), rs.getString("organization_id"),
                rs.getString("tenant_id"), rs.getString("project_id"), rs.getString("room_id"),
                SetSupport.copy(JdbcSupport.stringArray(rs.getString("event_types"))), rs.getBoolean("enabled"));
    }

    private static final class SetSupport {
        private static java.util.Set<String> copy(List<String> values) {
            return java.util.Set.copyOf(values);
        }
    }
}
