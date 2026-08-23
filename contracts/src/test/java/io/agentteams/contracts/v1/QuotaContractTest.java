package io.agentteams.contracts.v1;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaContractTest {

    private static final EventMetadata METADATA = EventMetadata.newBuilder()
            .setEventId("quota-event-1")
            .setAgentId("worker-1")
            .setCorrelationId("call-1")
            .setTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
            .setTracestate("vendor=value")
            .build();

    private static final ProtocolVersion VERSION = ProtocolVersion.newBuilder()
            .setMajor(1)
            .setMinor(0)
            .build();

    @Test
    void roundTripsAcceptedAcquireWithScopeIdentityAndDeadline() throws Exception {
        AcquireQuotaRequest request = AcquireQuotaRequest.newBuilder()
                .setMetadata(METADATA)
                .setProtocolVersion(VERSION)
                .setTenantId("tenant-a")
                .setProjectId("project-a")
                .setIdempotencyKey("acquire-1")
                .setEstimatedTokens(4096)
                .setMaxConcurrent(2)
                .setDeadline(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                .build();
        AcquireQuotaResponse response = AcquireQuotaResponse.newBuilder()
                .setMetadata(METADATA)
                .setProtocolVersion(VERSION)
                .setAccepted(true)
                .setReservationId("reservation-1")
                .build();

        assertEquals(request, AcquireQuotaRequest.parseFrom(request.toByteArray()));
        assertEquals(response, AcquireQuotaResponse.parseFrom(response.toByteArray()));
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                request.getMetadata().getTraceparent());
        assertEquals(1_700_000_000L, request.getDeadline().getSeconds());
        assertEquals("reservation-1", response.getReservationId());
    }

    @Test
    void roundTripsRejectedAcquireWithStableDimensionAndRetryHint() throws Exception {
        AcquireQuotaResponse response = AcquireQuotaResponse.newBuilder()
                .setMetadata(METADATA)
                .setProtocolVersion(VERSION)
                .setAccepted(false)
                .setRejectionDimension(QuotaRejectionDimension.QUOTA_REJECTION_DIMENSION_DAILY_TOKENS)
                .setRetryAfterMs(30_000)
                .build();

        AcquireQuotaResponse decoded = AcquireQuotaResponse.parseFrom(response.toByteArray());

        assertEquals(response, decoded);
        assertEquals(QuotaRejectionDimension.QUOTA_REJECTION_DIMENSION_DAILY_TOKENS,
                decoded.getRejectionDimension());
        assertEquals(30_000, decoded.getRetryAfterMs());
        assertFalse(decoded.getAccepted());
    }

    @Test
    void roundTripsReleaseWithReservationAndProtocolError() throws Exception {
        ReleaseQuotaRequest request = ReleaseQuotaRequest.newBuilder()
                .setMetadata(METADATA)
                .setProtocolVersion(VERSION)
                .setTenantId("tenant-a")
                .setProjectId("project-a")
                .setReservationId("reservation-1")
                .setIdempotencyKey("release-1")
                .setDeadline(Timestamp.newBuilder().setSeconds(1_700_000_001L))
                .build();
        ReleaseQuotaResponse response = ReleaseQuotaResponse.newBuilder()
                .setMetadata(METADATA)
                .setProtocolVersion(VERSION)
                .setAccepted(false)
                .setReservationId("reservation-1")
                .setProtocolError(QuotaProtocolError.QUOTA_PROTOCOL_ERROR_RESERVATION_NOT_FOUND)
                .build();

        assertEquals(request, ReleaseQuotaRequest.parseFrom(request.toByteArray()));
        assertEquals(response, ReleaseQuotaResponse.parseFrom(response.toByteArray()));
        assertEquals("reservation-1", request.getReservationId());
        assertEquals(QuotaProtocolError.QUOTA_PROTOCOL_ERROR_RESERVATION_NOT_FOUND,
                response.getProtocolError());
    }

    @Test
    void preservesUnknownFieldsForForwardCompatibility() throws Exception {
        int futureFieldNumber = 100;
        AcquireQuotaRequest encoded = AcquireQuotaRequest.newBuilder()
                .setTenantId("tenant-a")
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(futureFieldNumber, UnknownFieldSet.Field.newBuilder()
                                .addLengthDelimited(ByteString.copyFromUtf8("future"))
                                .build())
                        .build())
                .build();

        AcquireQuotaRequest decoded = AcquireQuotaRequest.parseFrom(encoded.toByteArray());

        assertTrue(decoded.getUnknownFields().hasField(futureFieldNumber));
        assertEquals("tenant-a", decoded.getTenantId());
    }

    @Test
    void keepsStableFieldNumbersAndEnumValues() {
        assertField(AcquireQuotaRequest.getDescriptor(), "tenant_id", 3);
        assertField(AcquireQuotaRequest.getDescriptor(), "project_id", 4);
        assertField(AcquireQuotaRequest.getDescriptor(), "idempotency_key", 5);
        assertField(AcquireQuotaRequest.getDescriptor(), "estimated_tokens", 6);
        assertField(AcquireQuotaRequest.getDescriptor(), "max_concurrent", 7);
        assertField(AcquireQuotaRequest.getDescriptor(), "deadline", 8);
        assertField(AcquireQuotaResponse.getDescriptor(), "accepted", 3);
        assertField(AcquireQuotaResponse.getDescriptor(), "reservation_id", 4);
        assertField(AcquireQuotaResponse.getDescriptor(), "rejection_dimension", 5);
        assertField(AcquireQuotaResponse.getDescriptor(), "retry_after_ms", 6);
        assertField(AcquireQuotaResponse.getDescriptor(), "protocol_error", 7);
        assertField(ReleaseQuotaRequest.getDescriptor(), "reservation_id", 5);
        assertField(ReleaseQuotaRequest.getDescriptor(), "idempotency_key", 6);
        assertField(ReleaseQuotaRequest.getDescriptor(), "deadline", 7);

        assertEquals(0, QuotaRejectionDimension.QUOTA_REJECTION_DIMENSION_UNSPECIFIED.getNumber());
        assertEquals(1, QuotaRejectionDimension.QUOTA_REJECTION_DIMENSION_CONCURRENT_CALLS.getNumber());
        assertEquals(2, QuotaRejectionDimension.QUOTA_REJECTION_DIMENSION_DAILY_CALLS.getNumber());
        assertEquals(3, QuotaRejectionDimension.QUOTA_REJECTION_DIMENSION_DAILY_TOKENS.getNumber());
        assertEquals(0, QuotaProtocolError.QUOTA_PROTOCOL_ERROR_UNSPECIFIED.getNumber());
        assertEquals(1, QuotaProtocolError.QUOTA_PROTOCOL_ERROR_INVALID_ARGUMENT.getNumber());
        assertEquals(6, QuotaProtocolError.QUOTA_PROTOCOL_ERROR_UNSUPPORTED_VERSION.getNumber());
    }

    @Test
    void containsNoSecretPromptOrResponseDataFields() {
        Set<String> forbidden = Set.of("secret", "prompt", "response");

        for (Descriptor descriptor : QuotaProto.getDescriptor().getMessageTypes()) {
            for (FieldDescriptor field : descriptor.getFields()) {
                assertFalse(forbidden.contains(field.getName()),
                        () -> descriptor.getName() + " contains forbidden field " + field.getName());
            }
        }
    }

    private static void assertField(Descriptor descriptor, String name, int number) {
        FieldDescriptor field = descriptor.findFieldByName(name);
        assertTrue(field != null, () -> descriptor.getName() + " is missing field " + name);
        assertEquals(number, field.getNumber(), descriptor.getName() + "." + name);
    }
}
