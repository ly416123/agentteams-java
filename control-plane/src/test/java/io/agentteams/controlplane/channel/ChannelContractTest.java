package io.agentteams.controlplane.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChannelContractTest {
    @Test
    void messageRequiresStableTenantBindingAndEventData() {
        ChannelMessage message = new ChannelMessage(UUID.randomUUID(), "org-1", "tenant-1", "project-1",
                "binding-1", "task.completed", "done", "corr-1");

        assertThat(message.channelType()).isEqualTo(ChannelType.WEBHOOK);
        assertThat(message.renderedBody()).isEqualTo("done");
    }

    @Test
    void rejectsMissingScopeOrPayload() {
        assertThatThrownBy(() -> new ChannelMessage(UUID.randomUUID(), "org-1", "", "project-1", "binding-1",
                "task.completed", "done", "corr-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenantId");
        assertThatThrownBy(() -> new ChannelMessage(UUID.randomUUID(), "org-1", "tenant-1", "project-1", "binding-1",
                "task.completed", "", "corr-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("renderedBody");
    }

    @Test
    void errorCategoriesAreBounded() {
        assertThat(ChannelErrorCategory.values()).containsExactly(
                ChannelErrorCategory.AUTH_REJECTED,
                ChannelErrorCategory.RATE_LIMITED,
                ChannelErrorCategory.TEMPORARILY_UNAVAILABLE,
                ChannelErrorCategory.INVALID_RESPONSE,
                ChannelErrorCategory.PERMANENT_REJECTION);
    }
}
