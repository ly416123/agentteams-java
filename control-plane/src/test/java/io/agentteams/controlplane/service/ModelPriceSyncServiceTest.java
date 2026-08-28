package io.agentteams.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.persistence.ModelPriceRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelPriceSyncServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

    @Test
    void importsConfiguredSnapshotAndIsIdempotentForExistingNaturalKey() {
        ModelPriceSyncPort source = mock(ModelPriceSyncPort.class);
        FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);
        ModelPriceSyncProperties.Target target = new ModelPriceSyncProperties.Target("tenant-a", "project-a");
        ModelPriceSyncPort.Quote quote = new ModelPriceSyncPort.Quote("openai", "gpt-5", "USD",
                new BigDecimal("1.25"), new BigDecimal("10"), NOW, null);
        when(source.fetch()).thenReturn(new ModelPriceSyncPort.Snapshot("catalog-1", List.of(quote)));
        when(persistence.findModelPriceByNaturalKey("tenant-a", "project-a", "openai", "gpt-5", "USD", NOW))
                .thenReturn(Optional.empty());
        ModelPriceRecord created = new ModelPriceRecord(UUID.randomUUID(), "tenant-a", "project-a", "openai",
                "gpt-5", "USD", quote.inputPricePerMillionTokens(), quote.outputPricePerMillionTokens(), NOW,
                null, "ACTIVE", NOW, NOW, 0, "price-sync", "price-sync");
        when(persistence.createModelPrice(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(created);

        ModelPriceSyncService service = new ModelPriceSyncService(source, persistence, List.of(target), 100);

        assertThat(service.runOnce(NOW)).isEqualTo(new ModelPriceSyncService.RunResult(1, 1, 1, 0));
        verify(persistence).createModelPrice(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());

        when(persistence.findModelPriceByNaturalKey("tenant-a", "project-a", "openai", "gpt-5", "USD", NOW))
                .thenReturn(Optional.of(created));
        assertThat(service.runOnce(NOW)).isEqualTo(new ModelPriceSyncService.RunResult(1, 1, 0, 1));
    }

    @Test
    void rejectsMissingConfiguredTargetBeforeFetching() {
        ModelPriceSyncPort source = mock(ModelPriceSyncPort.class);
        FoundationPersistenceService persistence = mock(FoundationPersistenceService.class);

        ModelPriceSyncService service = new ModelPriceSyncService(source, persistence,
                List.of(new ModelPriceSyncProperties.Target("tenant-a", "")), 100);

        assertThat(service.runOnce(NOW)).isEqualTo(new ModelPriceSyncService.RunResult(0, 0, 0, 0));
        org.mockito.Mockito.verifyNoInteractions(source, persistence);
    }
}
