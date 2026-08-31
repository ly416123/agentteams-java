package io.agentteams.controlplane.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ArtifactRetentionPolicyTest {
    @Test
    void acceptsNonNegativeRetentionWindowsAndKeepsLegalHoldExplicit() {
        ArtifactRetentionPolicy policy = new ArtifactRetentionPolicy(
                Duration.ofDays(30), Duration.ofDays(90), Duration.ofHours(2), true);

        assertThat(policy.successfulTaskRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(policy.failedTaskRetention()).isEqualTo(Duration.ofDays(90));
        assertThat(policy.temporaryUploadRetention()).isEqualTo(Duration.ofHours(2));
        assertThat(policy.legalHold()).isTrue();
    }

    @Test
    void rejectsNegativeWindows() {
        assertThatThrownBy(() -> new ArtifactRetentionPolicy(
                Duration.ofDays(-1), Duration.ofDays(90), Duration.ofHours(2), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("successfulTaskRetention");
    }
}
