package io.agentteams.controlplane.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.matrix.MatrixChannelBinding;
import io.agentteams.controlplane.matrix.MatrixChannelBindingRepository;
import io.agentteams.controlplane.matrix.MatrixOutboundRepository;
import io.agentteams.controlplane.matrix.MatrixRoomBinding;
import io.agentteams.controlplane.matrix.MatrixRoomBindingRepository;
import io.agentteams.controlplane.matrix.MatrixRoomBindingStatus;
import io.agentteams.controlplane.matrix.MatrixRoomContextType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatrixChannelAdapterTest {
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    @Test
    void queuesScopedMessageThroughMatrixOutbox() {
        MatrixChannelBindingRepository bindings = mock(MatrixChannelBindingRepository.class);
        MatrixRoomBindingRepository roomBindings = mock(MatrixRoomBindingRepository.class);
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        UUID bindingId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        MatrixChannelBinding channelBinding = binding(bindingId, true);
        when(bindings.findById(bindingId)).thenReturn(Optional.of(channelBinding));
        when(roomBindings.findById("tenant-1", channelBinding.roomBindingId()))
                .thenReturn(Optional.of(roomBinding(channelBinding.roomBindingId(), MatrixRoomBindingStatus.ACTIVE)));
        when(outbound.enqueue(any(UUID.class), any(String.class), any(), any(String.class), any(String.class),
                any(String.class), any(Instant.class)))
                .thenReturn(true);
        MatrixChannelAdapter adapter = new MatrixChannelAdapter(bindings, outbound, roomBindings, fixedClock());

        ChannelReceipt receipt = adapter.send(new ChannelMessage(messageId, "org-1", "tenant-1", "project-1",
                bindingId.toString(), "task.completed", "done", "corr-1", ChannelType.MATRIX));

        assertThat(receipt).isEqualTo(new ChannelReceipt(messageId, bindingId.toString(), ChannelReceiptStatus.QUEUED,
                null));
        verify(outbound).enqueue(messageId, "tenant-1", channelBinding.roomBindingId(), "!room:example.org",
                "task.completed", "done", NOW);
    }

    @Test
    void duplicateMessageIsReportedWithoutSecondDelivery() {
        MatrixChannelBindingRepository bindings = mock(MatrixChannelBindingRepository.class);
        MatrixRoomBindingRepository roomBindings = mock(MatrixRoomBindingRepository.class);
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        UUID bindingId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        MatrixChannelBinding channelBinding = binding(bindingId, true);
        when(bindings.findById(bindingId)).thenReturn(Optional.of(channelBinding));
        when(roomBindings.findById("tenant-1", channelBinding.roomBindingId()))
                .thenReturn(Optional.of(roomBinding(channelBinding.roomBindingId(), MatrixRoomBindingStatus.ACTIVE)));
        when(outbound.enqueue(messageId, "tenant-1", channelBinding.roomBindingId(), "!room:example.org",
                "task.completed", "done", NOW))
                .thenReturn(false);
        MatrixChannelAdapter adapter = new MatrixChannelAdapter(bindings, outbound, roomBindings, fixedClock());

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

    @Test
    void doesNotSendWhenTheLinkedRoomBindingWasRevoked() {
        MatrixChannelBindingRepository bindings = mock(MatrixChannelBindingRepository.class);
        MatrixRoomBindingRepository roomBindings = mock(MatrixRoomBindingRepository.class);
        MatrixOutboundRepository outbound = mock(MatrixOutboundRepository.class);
        UUID bindingId = UUID.randomUUID();
        UUID roomBindingId = UUID.randomUUID();
        when(bindings.findById(bindingId)).thenReturn(Optional.of(new MatrixChannelBinding(bindingId, "org-1",
                "tenant-1", "project-1", "!room:example.org", Set.of("task.completed"), true, roomBindingId,
                MatrixRoomContextType.TASK)));
        MatrixRoomBinding revoked = new MatrixRoomBinding(roomBindingId, "org-1", "tenant-1", "!room:example.org",
                MatrixRoomContextType.TASK, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                MatrixRoomBindingStatus.REVOKED, "admin", NOW, NOW, 1);
        when(roomBindings.findById("tenant-1", roomBindingId)).thenReturn(Optional.of(revoked));
        MatrixChannelAdapter adapter = new MatrixChannelAdapter(bindings, outbound, roomBindings, fixedClock());

        assertThatThrownBy(() -> adapter.send(new ChannelMessage(UUID.randomUUID(), "org-1", "tenant-1", "project-1",
                bindingId.toString(), "task.completed", "done", "corr-1", ChannelType.MATRIX)))
                .isInstanceOf(ChannelDeliveryException.class)
                .extracting(error -> ((ChannelDeliveryException) error).category())
                .isEqualTo(ChannelErrorCategory.PERMANENT_REJECTION);
        verifyNoInteractions(outbound);
    }

    private static MatrixChannelBinding binding(UUID id, boolean enabled) {
        return new MatrixChannelBinding(id, "org-1", "tenant-1", "project-1", "!room:example.org",
                Set.of("task.completed"), enabled,
                UUID.nameUUIDFromBytes(id.toString().getBytes(StandardCharsets.UTF_8)),
                MatrixRoomContextType.TASK);
    }

    private static MatrixRoomBinding roomBinding(UUID id, MatrixRoomBindingStatus status) {
        return new MatrixRoomBinding(id, "org-1", "tenant-1", "!room:example.org", MatrixRoomContextType.TASK,
                UUID.nameUUIDFromBytes(id.toString().getBytes(StandardCharsets.UTF_8)), UUID.randomUUID(),
                UUID.randomUUID(), status,
                "admin", NOW, NOW, 1);
    }

    private static ChannelMessage message(UUID bindingId, String tenantId, String eventType) {
        return new ChannelMessage(UUID.randomUUID(), "org-1", tenantId, "project-1", bindingId.toString(), eventType,
                "done", "corr-1", ChannelType.MATRIX);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
