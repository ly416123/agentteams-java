package io.agentteams.controlplane.quota;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.manager.QuotaLease;
import io.agentteams.manager.QuotaRejectedException;
import org.junit.jupiter.api.Test;

class ProjectQuotaPortAdapterTest {
    @Test
    void translatesProjectQuotaRejectionAtTheModuleBoundary() {
        ProjectQuotaService service = mock(ProjectQuotaService.class);
        when(service.acquire("tenant-a", "project-a", 100))
                .thenThrow(new QuotaExceededException("daily_tokens"));

        ProjectQuotaPortAdapter adapter = new ProjectQuotaPortAdapter(service);

        assertThatThrownBy(() -> adapter.acquire("tenant-a", "project-a", 100))
                .isInstanceOf(QuotaRejectedException.class)
                .hasMessage("quota rejected: daily_tokens")
                .hasCauseInstanceOf(QuotaExceededException.class);
    }

    @Test
    void releaseDelegatesOnceThroughIdempotentPortLease() {
        ProjectQuotaService service = mock(ProjectQuotaService.class);
        ProjectQuotaLease projectLease = new ProjectQuotaLease("tenant-a", "project-a", true);
        when(service.acquire("tenant-a", "project-a", 100)).thenReturn(projectLease);

        ProjectQuotaPortAdapter adapter = new ProjectQuotaPortAdapter(service);
        QuotaLease lease = adapter.acquire("tenant-a", "project-a", 100);
        lease.close();
        lease.close();

        verify(service).release(projectLease);
    }
}
