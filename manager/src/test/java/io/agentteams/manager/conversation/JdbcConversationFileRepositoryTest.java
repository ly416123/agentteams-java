package io.agentteams.manager.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcConversationFileRepositoryTest {
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID FILE = UUID.randomUUID();

    @Test
    void insertBindsAllColumns() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcConversationFileRepository repository = new JdbcConversationFileRepository(jdbc);
        ConversationFile file = new ConversationFile(FILE, SESSION, "report.pdf", "application/pdf",
                36881L, "conversations/" + SESSION + "/files/" + FILE + "/report.pdf", Instant.EPOCH);
        repository.insert(file);
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void findQueriesBySessionAndFileId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new ConversationFile(FILE, SESSION, "report.pdf", "application/pdf",
                        36881L, "conversations/" + SESSION + "/files/" + FILE + "/report.pdf", Instant.EPOCH)));
        JdbcConversationFileRepository repository = new JdbcConversationFileRepository(jdbc);
        ConversationFile found = repository.find(SESSION, FILE);
        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo(FILE);
        assertThat(found.sessionId()).isEqualTo(SESSION);
    }

    @SuppressWarnings("unchecked")
    @Test
    void findReturnsNullWhenMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        assertThat(new JdbcConversationFileRepository(jdbc).find(SESSION, FILE)).isNull();
    }
}
