package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.contracts.v1.ServerMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConfigChangedCommandHandlerTest {
    @Test
    void convertsAConfigChangedOutboxEventIntoDurableGatewayCommand() throws Exception {
        CommandDeliveryService delivery = mock(CommandDeliveryService.class);
        ConfigChangedCommandHandler handler = new ConfigChangedCommandHandler(delivery, new ObjectMapper());
        UUID agentId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String manifest = "{\"model\":\"deepseek\"}";
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(manifest.getBytes(StandardCharsets.UTF_8)));
        String payload = "{" +
                "\"eventId\":\"" + eventId + "\"," +
                "\"agentId\":\"" + agentId + "\"," +
                "\"bindingId\":\"" + bindingId + "\"," +
                "\"snapshotId\":\"" + snapshotId + "\"," +
                "\"configVersion\":3," +
                "\"manifestJson\":\"{\\\"model\\\":\\\"deepseek\\\"}\", " +
                "\"manifestSha256\":\"" + checksum + "\",\"sizeBytes\":" + manifest.length() + "," +
                "\"files\":[{\"path\":\"models/default.json\",\"uri\":\"urn:agentteams:config-file:"
                + snapshotId + ":models/default.json\",\"sha256\":\"file-sha\",\"sizeBytes\":42,"
                + "\"contentType\":\"application/json\"}]}";

        assertThat(handler.handle("ConfigChanged", agentId.toString(), payload,
                Instant.parse("2026-08-19T00:00:00Z"))).isTrue();

        ArgumentCaptor<ServerMessage> message = ArgumentCaptor.forClass(ServerMessage.class);
        verify(delivery).deliver(eq(agentId.toString()), message.capture());
        assertThat(message.getValue().getConfigChanged().getRollback()).isFalse();
        assertThat(message.getValue().getConfigChanged().getFilesList()).hasSize(1);
        assertThat(message.getValue().getConfigChanged().getFiles(0).getPath()).isEqualTo("models/default.json");
        assertThat(message.getValue().getConfigChanged().getFiles(0).getUri())
                .isEqualTo("urn:agentteams:config-file:" + snapshotId + ":models/default.json");
    }
}
