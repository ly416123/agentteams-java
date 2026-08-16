package io.agentteams.controlplane.matrix;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Loads Matrix-to-platform identity mappings without exposing persistence errors to callers. */
public final class JdbcMatrixIdentityBinder implements MatrixIdentityBinder {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SQL = """
            SELECT matrix_user_id, subject, tenant, project, team, permissions
              FROM platform_identities
             WHERE matrix_user_id = ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcMatrixIdentityBinder(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<MatrixIdentity> bind(String matrixUserId) {
        if (matrixUserId == null || matrixUserId.isBlank()) return Optional.empty();
        try {
            return jdbc.query(SQL, (rs, row) -> {
                try {
                    List<String> permissions = MAPPER.readValue(rs.getString("permissions"),
                            new TypeReference<List<String>>() { });
                    Principal principal = new Principal(rs.getString("subject"),
                            new AuthorizationService.Scope(rs.getString("tenant"),
                                    rs.getString("project"), rs.getString("team")),
                            java.util.Set.copyOf(permissions));
                    return new MatrixIdentity(rs.getString("matrix_user_id"), principal);
                } catch (Exception error) {
                    throw new MatrixIdentityServiceException("Matrix identity mapping is invalid", error);
                }
            }, matrixUserId).stream().findFirst();
        } catch (MatrixIdentityServiceException error) {
            throw error;
        } catch (DataAccessException error) {
            throw new MatrixIdentityServiceException("Matrix identity service unavailable", error);
        }
    }
}
