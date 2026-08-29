package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.agentteams.manager.conversation.ConversationRuntimeConfiguration;
import io.agentteams.manager.conversation.ConversationRuntimePort;
import io.agentteams.manager.conversation.ConversationService;
import io.agentteams.manager.conversation.FakeConversationRuntime;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ManagerApplicationCompositionTest {
    @Test
    void productionCompositionUsesDurableAuditAndProjectScopedAdmission() {
        ManagerApplication application = new ManagerApplication();
        JdbcModelCallAuditor auditor = application.managerModelCallAuditor(mock(JdbcTemplate.class));
        ModelCallAdmission admission = application.managerModelCallAdmission(QuotaPort.noop());

        assertThat(auditor).isInstanceOf(JdbcModelCallAuditor.class);
        assertThat(admission).isInstanceOf(ProjectScopedModelCallAdmission.class);
    }

    @Test
    void defaultConversationCompositionUsesFakeWithoutOpeningAConnection() {
        ManagerApplication application = new ManagerApplication();
        ConversationRuntimeConfiguration configuration = new ConversationRuntimeConfiguration(
                URI.create("http://127.0.0.1:1"), "agent-a", null,
                Duration.ofMillis(50), Duration.ofMillis(100), 1024, "agentteams", "console");

        ConversationRuntimePort runtime = application.conversationRuntime("fake", configuration);

        assertThat(runtime).isInstanceOf(FakeConversationRuntime.class);
        assertThat(application.conversationService(runtime)).isInstanceOf(ConversationService.class);
    }

    @Test
    void qwenpawConversationCompositionIsSelectedExplicitly() {
        ManagerApplication application = new ManagerApplication();
        ConversationRuntimeConfiguration configuration = new ConversationRuntimeConfiguration(
                URI.create("http://127.0.0.1:1"), "agent-a", null,
                Duration.ofMillis(50), Duration.ofMillis(100), 1024, "agentteams", "console");

        ConversationRuntimePort runtime = application.conversationRuntime("qwenpaw", configuration);

        assertThat(runtime).isInstanceOf(io.agentteams.manager.conversation.QwenPawConversationRuntime.class);
        runtime.close();
    }
}
