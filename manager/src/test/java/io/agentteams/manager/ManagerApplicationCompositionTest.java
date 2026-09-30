package io.agentteams.manager;

import io.agentteams.application.api.QuotaPort;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
        ConversationRuntimePort runtime = application.conversationRuntime("fake");

        assertThat(runtime).isInstanceOf(FakeConversationRuntime.class);
        assertThat(application.conversationService(runtime)).isInstanceOf(ConversationService.class);
    }

}
