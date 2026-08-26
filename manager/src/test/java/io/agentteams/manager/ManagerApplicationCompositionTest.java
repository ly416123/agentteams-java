package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
}
