package io.agentteams.contracts.v1;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentChannelContractTest {

    private static final EventMetadata TASK_METADATA = EventMetadata.newBuilder()
            .setEventId("event-1")
            .setAgentId("agent-1")
            .setTaskId("task-1")
            .setAttemptId("attempt-1")
            .setSequence(7)
            .setExpectedVersion(3)
            .setLeaseId("lease-1")
            .setOccurredAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
            .build();

    private static final EventMetadata AGENT_METADATA = EventMetadata.newBuilder()
            .setEventId("event-2")
            .setAgentId("agent-1")
            .setSequence(8)
            .setOccurredAt(Timestamp.newBuilder().setSeconds(1_700_000_001L).build())
            .build();

    @Test
    void roundTripsProtocolVersion() throws Exception {
        ProtocolVersion version = ProtocolVersion.newBuilder()
                .setMajor(1)
                .setMinor(3)
                .build();

        assertEquals(version, ProtocolVersion.parseFrom(version.toByteArray()));
    }

    @Test
    void roundTripsEveryAgentMessageEnvelope() throws Exception {
        assertRoundTrip(
                AgentMessage.newBuilder().setHello(AgentHello.newBuilder()
                        .setMetadata(AGENT_METADATA)
                        .setProtocolVersion(ProtocolVersion.newBuilder().setMajor(1).setMinor(2))
                        .setRuntimeName("qwenpaw")
                        .setRuntimeVersion("0.4.0")
                        .putCapabilities("tasks", "1")
                        .setMaxConcurrentTasks(4)
                        .setConfigVersion(12)
                        .setMaxWorkspaceBytes(10_000)
                        .setMaxArtifactBytes(20_000)
                        .setSpecDigest("sha256:worker-v2")
                        .setConfigRevision("config-17")
                        .setSecretGeneration("secret-9")
                        .build()).build(),
                AgentMessage.PayloadCase.HELLO);

        assertRoundTrip(
                AgentMessage.newBuilder().setTaskAccepted(TaskAccepted.newBuilder()
                        .setMetadata(TASK_METADATA)
                        .setAccepted(true)
                        .build()).build(),
                AgentMessage.PayloadCase.TASK_ACCEPTED);

        assertRoundTrip(
                AgentMessage.newBuilder().setTaskProgress(TaskProgress.newBuilder()
                        .setMetadata(TASK_METADATA)
                        .setPercent(50)
                        .setStatus("running")
                        .setMessage("halfway")
                        .build()).build(),
                AgentMessage.PayloadCase.TASK_PROGRESS);

        assertRoundTrip(
                AgentMessage.newBuilder().setTaskHeartbeat(TaskHeartbeat.newBuilder()
                        .setMetadata(TASK_METADATA)
                        .setStatus("running")
                        .setLeaseExpiresAt(Timestamp.newBuilder().setSeconds(1_700_000_030L))
                        .build()).build(),
                AgentMessage.PayloadCase.TASK_HEARTBEAT);

        assertRoundTrip(
                AgentMessage.newBuilder().setTaskCompleted(TaskCompleted.newBuilder()
                        .setMetadata(TASK_METADATA)
                        .setResultJson(ByteString.copyFromUtf8("{\"ok\":true}"))
                        .addArtifacts(ArtifactRef.newBuilder()
                                .setName("result.txt")
                                .setUri("s3://bucket/result.txt")
                                .setSha256("abc123")
                                .setSizeBytes(12)
                                .build())
                        .build()).build(),
                AgentMessage.PayloadCase.TASK_COMPLETED);

        assertRoundTrip(
                AgentMessage.newBuilder().setTaskFailed(TaskFailed.newBuilder()
                        .setMetadata(TASK_METADATA)
                        .setCode("RUNTIME_ERROR")
                        .setMessage("worker failed")
                        .setRetryable(true)
                        .build()).build(),
                AgentMessage.PayloadCase.TASK_FAILED);

        assertRoundTrip(
                AgentMessage.newBuilder().setConfigApplied(ConfigApplied.newBuilder()
                        .setMetadata(AGENT_METADATA)
                        .setConfigVersion(12)
                        .setApplied(true)
                        .addResourceResults(ResourceApplyResult.newBuilder()
                                .setType("SKILL")
                                .setResourceId("skill-a")
                                .setRevision("7")
                                .setExpectedDigest("sha256:expected")
                                .setObservedDigest("sha256:observed")
                                .setStatus(ResourceApplyResult.Status.APPLIED)
                                .build())
                        .build()).build(),
                AgentMessage.PayloadCase.CONFIG_APPLIED);

        assertRoundTrip(
                AgentMessage.newBuilder().setAck(Ack.newBuilder()
                        .setMetadata(AGENT_METADATA)
                        .setAckedEventId("event-0")
                        .setAckedSequence(6)
                        .build()).build(),
                AgentMessage.PayloadCase.ACK);

        assertRoundTrip(
                AgentMessage.newBuilder().setError(Error.newBuilder()
                        .setMetadata(AGENT_METADATA)
                        .setCode("INVALID_MESSAGE")
                        .setMessage("message cannot be handled")
                        .setRetryable(false)
                        .setRejectedEventId("event-0")
                        .build()).build(),
                AgentMessage.PayloadCase.ERROR);
    }

    @Test
    void roundTripsEveryServerMessageEnvelope() throws Exception {
        assertRoundTrip(
                ServerMessage.newBuilder().setReady(AgentReady.newBuilder()
                        .setMetadata(AGENT_METADATA)
                        .setAccepted(true)
                        .setNegotiatedVersion(ProtocolVersion.newBuilder().setMajor(1).setMinor(2))
                        .build()).build(),
                ServerMessage.PayloadCase.READY);

        assertRoundTrip(
                ServerMessage.newBuilder().setTaskAssigned(TaskAssigned.newBuilder()
                        .setMetadata(TASK_METADATA)
                        .setTaskType("summarize")
                        .setInputJson(ByteString.copyFromUtf8("{\"text\":\"hello\"}"))
                        .addRequiredCapabilities("summarization")
                        .setLeaseExpiresAt(Timestamp.newBuilder().setSeconds(1_700_000_030L))
                        .build()).build(),
                ServerMessage.PayloadCase.TASK_ASSIGNED);

                assertRoundTrip(
                ServerMessage.newBuilder().setConfigChanged(ConfigChanged.newBuilder()
                        .setMetadata(AGENT_METADATA)
                        .setConfigVersion(13)
                        .setManifestUri("s3://bucket/config-13.json")
                        .setManifestSha256("def456")
                        .setSizeBytes(128)
                        .addFiles(ConfigFile.newBuilder()
                                .setPath("models/default.json")
                                .setUri("urn:agentteams:config-file:snapshot-13:models/default.json")
                                .setSha256("file-sha256")
                                .setSizeBytes(42)
                                .setContentType("application/json")
                                .build())
                        .build()).build(),
                ServerMessage.PayloadCase.CONFIG_CHANGED);

        assertRoundTrip(
                ServerMessage.newBuilder().setAck(Ack.newBuilder()
                        .setMetadata(AGENT_METADATA)
                        .setAckedEventId("event-3")
                        .setAckedSequence(9)
                        .build()).build(),
                ServerMessage.PayloadCase.ACK);

        assertRoundTrip(
                ServerMessage.newBuilder().setError(Error.newBuilder()
                        .setMetadata(AGENT_METADATA)
                        .setCode("VERSION_REJECTED")
                        .setMessage("unsupported protocol version")
                        .setRetryable(false)
                        .build()).build(),
                ServerMessage.PayloadCase.ERROR);
    }

    @Test
    void messageMetadataCarriesDeliveryIdentityAndOrderingFields() {
        assertEquals("event-1", TASK_METADATA.getEventId());
        assertEquals("agent-1", TASK_METADATA.getAgentId());
        assertEquals("task-1", TASK_METADATA.getTaskId());
        assertEquals("attempt-1", TASK_METADATA.getAttemptId());
        assertEquals(7, TASK_METADATA.getSequence());
        assertEquals(3, TASK_METADATA.getExpectedVersion());
        assertEquals("lease-1", TASK_METADATA.getLeaseId());
        assertEquals(1_700_000_000L, TASK_METADATA.getOccurredAt().getSeconds());
    }

    @Test
    void additiveExpectedVersionAndLeaseIdFieldsSurviveWireSerialization() throws Exception {
        EventMetadata decoded = EventMetadata.parseFrom(TASK_METADATA.toByteArray());

        assertEquals(7, EventMetadata.getDescriptor().findFieldByName("expected_version").getNumber());
        assertEquals(8, EventMetadata.getDescriptor().findFieldByName("lease_id").getNumber());
        assertEquals(3, decoded.getExpectedVersion());
        assertEquals("lease-1", decoded.getLeaseId());
    }

    @Test
    void workerVersionFactsUseAdditiveFieldsAndOldHelloDefaults() throws Exception {
        assertEquals(10, AgentHello.getDescriptor().findFieldByName("spec_digest").getNumber());
        assertEquals(11, AgentHello.getDescriptor().findFieldByName("config_revision").getNumber());
        assertEquals(12, AgentHello.getDescriptor().findFieldByName("secret_generation").getNumber());

        AgentHello oldHello = AgentHello.newBuilder()
                .setRuntimeName("qwenpaw")
                .setRuntimeVersion("0.4.0")
                .build();
        AgentHello decoded = AgentHello.parseFrom(oldHello.toByteArray());

        assertEquals("", decoded.getSpecDigest());
        assertEquals("", decoded.getConfigRevision());
        assertEquals("", decoded.getSecretGeneration());
        assertTrue(!decoded.hasSpecDigest());
        assertTrue(!decoded.hasConfigRevision());
        assertTrue(!decoded.hasSecretGeneration());
    }

    @Test
    void oldAgentSafelyIgnoresUnknownAgentEnvelopeField() throws Exception {
        int futureFieldNumber = 100;
        AgentMessage encoded = AgentMessage.newBuilder()
                .setUnknownFields(unknownFutureField(futureFieldNumber))
                .build();

        AgentMessage decoded = AgentMessage.parseFrom(encoded.toByteArray());

        assertEquals(AgentMessage.PayloadCase.PAYLOAD_NOT_SET, decoded.getPayloadCase());
        assertTrue(decoded.getUnknownFields().hasField(futureFieldNumber));
    }

    @Test
    void oldAgentSafelyIgnoresUnknownServerEnvelopeField() throws Exception {
        int futureFieldNumber = 100;
        ServerMessage encoded = ServerMessage.newBuilder()
                .setUnknownFields(unknownFutureField(futureFieldNumber))
                .build();

        ServerMessage decoded = ServerMessage.parseFrom(encoded.toByteArray());

        assertEquals(ServerMessage.PayloadCase.PAYLOAD_NOT_SET, decoded.getPayloadCase());
        assertTrue(decoded.getUnknownFields().hasField(futureFieldNumber));
    }

    private static UnknownFieldSet unknownFutureField(int fieldNumber) {
        return UnknownFieldSet.newBuilder()
                .addField(fieldNumber, UnknownFieldSet.Field.newBuilder()
                        .addLengthDelimited(ByteString.copyFromUtf8("future-payload"))
                        .build())
                .build();
    }

    private static void assertRoundTrip(AgentMessage message, AgentMessage.PayloadCase expectedCase)
            throws Exception {
        AgentMessage decoded = AgentMessage.parseFrom(message.toByteArray());
        assertEquals(expectedCase, decoded.getPayloadCase());
        assertEquals(message, decoded);
    }

    private static void assertRoundTrip(ServerMessage message, ServerMessage.PayloadCase expectedCase)
            throws Exception {
        ServerMessage decoded = ServerMessage.parseFrom(message.toByteArray());
        assertEquals(expectedCase, decoded.getPayloadCase());
        assertEquals(message, decoded);
    }
}
