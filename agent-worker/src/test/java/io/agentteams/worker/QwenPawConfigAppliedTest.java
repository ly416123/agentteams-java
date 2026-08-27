package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.ConfigApplied;
import io.agentteams.contracts.v1.ConfigChanged;
import io.agentteams.contracts.v1.EventMetadata;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class QwenPawConfigAppliedTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void carriesStableResourceBindingFailureThroughExistingConfigAppliedMessage() {
        ConfigChanged changed = ConfigChanged.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                        .setEventId("config-event-1")
                        .setAgentId("agent-1")
                        .setOccurredAt(Timestamp.getDefaultInstance())
                        .build())
                .setConfigVersion(7)
                .setBindingId("00000000-0000-0000-0000-000000000007")
                .setSnapshotId("00000000-0000-0000-0000-000000000008")
                .build();

        ConfigApplied result = QwenPawWorker.configApplied(changed, false,
                "RESOURCE_BINDING_INVALID: index:0=INVALID_REVISION", CLOCK);

        assertThat(result.getApplied()).isFalse();
        assertThat(result.getErrorMessage())
                .isEqualTo("RESOURCE_BINDING_INVALID: index:0=INVALID_REVISION");
        assertThat(result.getConfigVersion()).isEqualTo(7);
        assertThat(result.getBindingId()).isEqualTo(changed.getBindingId());
        assertThat(result.getSnapshotId()).isEqualTo(changed.getSnapshotId());
        assertThat(result.getMetadata().getEventId()).isEqualTo(changed.getMetadata().getEventId());
        assertThat(result.getMetadata().getAgentId()).isEqualTo("agent-1");
        assertThat(result.getMetadata().getOccurredAt().getSeconds())
                .isEqualTo(Instant.parse("2026-08-23T00:00:00Z").getEpochSecond());
    }

    @Test
    void successfulConfigAppliedAckNeverCarriesAStaleError() {
        ConfigChanged changed = ConfigChanged.newBuilder()
                .setMetadata(EventMetadata.newBuilder().setEventId("config-event-2").setAgentId("agent-1").build())
                .setConfigVersion(8)
                .setBindingId("00000000-0000-0000-0000-000000000009")
                .setSnapshotId("00000000-0000-0000-0000-000000000010")
                .build();

        assertThat(QwenPawWorker.configApplied(changed, true, "old failure", CLOCK).getErrorMessage())
                .isEmpty();
    }

    @Test
    void carriesStructuredResourceApplyResults() {
        ConfigChanged changed = ConfigChanged.newBuilder()
                .setMetadata(EventMetadata.newBuilder().setEventId("config-event-3").setAgentId("agent-1").build())
                .setConfigVersion(9)
                .setBindingId("00000000-0000-0000-0000-000000000011")
                .setSnapshotId("00000000-0000-0000-0000-000000000012")
                .build();

        var result = io.agentteams.contracts.v1.ResourceApplyResult.newBuilder()
                .setType("MCP").setResourceId("server-a").setRevision("3")
                .setExpectedDigest("sha256:mcp")
                .setStatus(io.agentteams.contracts.v1.ResourceApplyResult.Status.APPLIED)
                .build();

        assertThat(QwenPawWorker.configApplied(changed, true, "", List.of(result), CLOCK)
                .getResourceResultsList()).containsExactly(result);
    }
}
