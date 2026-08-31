package io.agentteams.controlplane.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.matrix.MatrixChannelBinding;
import io.agentteams.controlplane.matrix.MatrixChannelBindingRepository;
import io.agentteams.controlplane.matrix.MatrixOutboundRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatrixChannelAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    @Test
    void queuesScopedMessageThroughMatrixOutbox() {
        MatrixChannelBindingRepository bindings = mock(MatrixChannelBindingRepository.class);
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        UUID bindingId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(bindings.findById(bindingId)).thenReturn(Optional.of(binding(bindingId, true)));
        when(outbound.enqueue(any(UUID.class), any(String.class), any(String.class), any(String.class), any(Instant.class)))
                .thenReturn(true);
        MatrixChannelAdapter adapter = new MatrixChannelAdapter(bindings, outbound, fixedClock());

        ChannelReceipt receipt = adapter.send(new ChannelMessage(messageId, "org-1", "tenant-1", "project-1",
                bindingId.toString(), "task.completed", "done", "corr-1", ChannelType.MATRIX));

        assertThat(receipt).isEqualTo(new ChannelReceipt(messageId, bindingId.toString(), ChannelReceiptStatus.QUEUED,
                null));
        verify(outbound).enqueue(messageId, "!room:example.org", "task.completed", "done", NOW);
    }

    @Test
    void duplicateMessageIsReportedWithoutSecondDelivery() {
        MatrixChannelBindingRepository bindings = mock(MatrixChannelBindingRepository.class);
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        UUID bindingId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        when(bindings.findById(bindingId)).thenReturn(Optional.of(binding(bindingId, true)));
        when(outbound.enqueue(messageId, "!room:example.org", "task.completed", "done", NOW)).thenReturn(false);
        MatrixChannelAdapter adapter = new MatrixChannelAdapter(bindings, outbound, fixedClock());

        ChannelReceipt receipt = adapter.send(new ChannelMessage(messageId, "org-1", "tenant-1", "project-1",
                bindingId.toString(), "task.completed", "done", "corr-1", ChannelType.MATRIX));

        assertThat(receipt.status()).isEqualTo(ChannelReceiptStatus.DUPLICATE);
    }

    @Test
    void rejectsCrossTenantDisabledAndUnallowedEvents() {
        MatrixChannelBindingRepository bindings = mock(MatrixChannelBindingRepository.class);
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        UUID bindingId = UUID.randomUUID();
        UUID enabledBindingId = UUID.randomUUID();
        when(bindings.findById(bindingId)).thenReturn(Optional.of(binding(bindingId, false)));
        when(bindings.findById(enabledBindingId)).thenReturn(Optional.of(binding(enabledBindingId, true)));
        MatrixChannelAdapter adapter = new MatrixChannelAdapter(bindings, outbound, fixedClock());

        assertThatThrownBy(() -> adapter.send(message(bindingId, "tenant-2", "task.completed")))
                .isInstanceOf(ChannelDeliveryException.class)
                .extracting(error -> ((ChannelDeliveryException) error).category())
                .isEqualTo(ChannelErrorCategory.AUTH_REJECTED);
        assertThatThrownBy(() -> adapter.send(message(enabledBindingId, "tenant-1", "task.failed")))
                .isInstanceOf(ChannelDeliveryException.class)
                .extracting(error -> ((ChannelDeliveryException) error).category())
                .isEqualTo(ChannelErrorCategory.PERMANENT_REJECTION);
        assertThatThrownBy(() -> adapter.send(message(bindingId, "tenant-1", "task.completed")))
                .isInstanceOf(ChannelDeliveryException.class)
                .extracting(error -> ((ChannelDeliveryException) error).category())
                .isEqualTo(ChannelErrorCategory.PERMANENT_REJECTION);
    }

    @Test
    void healthFailsClosedForWrongScopeAndMissingBinding() {
        MatrixChannelBindingRepository bindings = mock(MatrixChannelBindingRepository.class);
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        UUID bindingId = UUID.randomUUID();
        when(bindings.findById(bindingId)).thenReturn(Optional.of(binding(bindingId, true)));
        MatrixChannelAdapter adapter = new MatrixChannelAdapter(bindings, outbound, fixedClock());

        ChannelHealth health = adapter.health(new ChannelBinding(ChannelType.MATRIX, bindingId.toString(), "org-1",
                "tenant-2", "project-1"));

        assertThat(health.status()).isEqualTo(ChannelHealthStatus.UNAVAILABLE);
        assertThat(health.errorCategory()).isEqualTo(ChannelErrorCategory.AUTH_REJECTED);
    }

    private static MatrixChannelBinding binding(UUID id, boolean enabled) {
        return new MatrixChannelBinding(id, "org-1", "tenant-1", "project-1", "!room:example.org",
                Set.of("task.completed"), enabled);
    }

    private static ChannelMessage message(UUID bindingId, String tenantId, String eventType) {
        return new ChannelMessage(UUID.randomUUID(), "org-1", tenantId, "project-1", bindingId.toString(), eventType,
                "done", "corr-1", ChannelType.MATRIX);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
