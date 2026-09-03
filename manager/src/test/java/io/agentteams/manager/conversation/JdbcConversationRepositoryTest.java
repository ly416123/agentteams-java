package io.agentteams.manager.conversation;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcConversationRepositoryTest {
    @Test
    void qualifiesSessionColumnsWhenJoiningProjectsForLegacyScopeCompatibility() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcConversationRepository repository = new JdbcConversationRepository(jdbc);

        repository.findSessions("tenant-a", "project-a", "actor-a", null, null, 20);

        verify(jdbc).query(contains("SELECT c.id, c.project_id, c.team_id, c.worker_id, c.task_id"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ConversationRepository.ConversationRecord>>any(),
                eq("tenant-a"), eq("project-a"), eq("actor-a"), eq(20));
        verify(jdbc).query(contains("ORDER BY c.updated_at DESC, c.id DESC"),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<ConversationRepository.ConversationRecord>>any(),
                eq("tenant-a"), eq("project-a"), eq("actor-a"), eq(20));
    }
}
