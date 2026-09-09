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
