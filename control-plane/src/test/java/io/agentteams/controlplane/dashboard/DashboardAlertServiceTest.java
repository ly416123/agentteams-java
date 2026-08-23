package io.agentteams.controlplane.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentteams.controlplane.usage.UsageQueryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DashboardAlertServiceTest {
    @Test
    void evaluatesRulesFromReplaceableRepository() {
        InMemoryDashboardAlertRuleRepository repository = new InMemoryDashboardAlertRuleRepository();
        repository.save(new DashboardAlertRule("FAILURE_RATE", "INFO", 0.25, true));
        DashboardAlertService service = new DashboardAlertService(repository);

        List<DashboardAlertService.Alert> alerts = service.evaluate(summary(4, 2, 0, 0));

        assertThat(alerts).containsExactly(new DashboardAlertService.Alert(
                "FAILURE_RATE", "INFO", 0.5, "failure rate exceeded configured threshold"));
    }

    @Test
    void defaultRulesRemainCompatibleAndDisabledRulesAreIgnored() {
        InMemoryDashboardAlertRuleRepository repository = new InMemoryDashboardAlertRuleRepository();
        repository.save(new DashboardAlertRule("COST", "WARNING", 1, false));

        assertThat(new DashboardAlertService(repository).evaluate(summary(1, 0, 0, 1000))).isEmpty();
        assertThat(new DashboardAlertService().evaluate(summary(1, 1, 0, 0))).extracting(
                DashboardAlertService.Alert::rule).containsExactly("FAILURE_RATE");
    }

    @Test
    void repositoryStoresDefensiveImmutableSnapshots() {
        InMemoryDashboardAlertRuleRepository repository = new InMemoryDashboardAlertRuleRepository();
        repository.save(new DashboardAlertRule("cost", "warning", 10, true));

        assertThat(repository.findAll()).containsExactly(new DashboardAlertRule("COST", "WARNING", 10, true));
        List<DashboardAlertRule> snapshot = repository.findAll();
        assertThatThrownBy(() -> snapshot.add(new DashboardAlertRule("OTHER", "INFO", 1, true)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static DashboardSummaryController.DashboardSummary summary(long calls, long failures,
            double latency, double cost) {
        return new DashboardSummaryController.DashboardSummary(Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                calls, failures, 0, 0, cost, latency, List.of());
    }
}
