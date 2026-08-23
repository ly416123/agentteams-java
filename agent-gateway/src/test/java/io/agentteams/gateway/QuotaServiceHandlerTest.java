package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import io.agentteams.application.api.QuotaReservationPort;
import io.agentteams.contracts.v1.AcquireQuotaRequest;
import io.agentteams.contracts.v1.AcquireQuotaResponse;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.agentteams.contracts.v1.QuotaProtocolError;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuotaServiceHandlerTest {

    @Test
    void mapsAcquireToApplicationPortAndPreservesTraceMetadata() {
        List<QuotaReservationPort.AcquireRequest> calls = new ArrayList<>();
        QuotaServiceHandler handler = new QuotaServiceHandler(new QuotaReservationPort() {
            @Override
            public AcquireDecision acquire(AcquireRequest request) {
                calls.add(request);
                return new AcquireDecision(true, "reservation-1", "", 0, "");
            }

            @Override
            public ReleaseDecision release(ReleaseRequest request) {
                return new ReleaseDecision(true, request.reservationId(), "");
            }
        });
        RecordingObserver<AcquireQuotaResponse> observer = new RecordingObserver<>();

        handler.acquire(request(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.values).singleElement().satisfies(response -> {
            assertThat(response.getAccepted()).isTrue();
            assertThat(response.getReservationId()).isEqualTo("reservation-1");
            assertThat(response.getMetadata().getTraceparent()).isEqualTo("00-abc-def-01");
        });
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.tenantId()).isEqualTo("tenant-1");
            assertThat(call.projectId()).isEqualTo("project-1");
            assertThat(call.estimatedTokens()).isEqualTo(42);
            assertThat(call.traceparent()).isEqualTo("00-abc-def-01");
        });
    }

    @Test
    void rejectsMalformedAcquireWithoutCallingPort() {
        QuotaServiceHandler handler = new QuotaServiceHandler(new QuotaReservationPort() {
            @Override
            public AcquireDecision acquire(AcquireRequest request) {
                throw new AssertionError("port must not be called");
            }

            @Override
            public ReleaseDecision release(ReleaseRequest request) {
                throw new AssertionError("port must not be called");
            }
        });
        RecordingObserver<AcquireQuotaResponse> observer = new RecordingObserver<>();

        handler.acquire(request().toBuilder().clearIdempotencyKey().build(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.values).singleElement()
                .extracting(AcquireQuotaResponse::getProtocolError)
                .isEqualTo(QuotaProtocolError.QUOTA_PROTOCOL_ERROR_INVALID_ARGUMENT);
    }

    private static AcquireQuotaRequest request() {
        return AcquireQuotaRequest.newBuilder()
                .setMetadata(EventMetadata.newBuilder().setEventId("event-1")
                        .setTraceparent("00-abc-def-01").setTracestate("vendor=value"))
                .setProtocolVersion(ProtocolVersion.newBuilder().setMajor(2).setMinor(3))
                .setTenantId("tenant-1").setProjectId("project-1")
                .setIdempotencyKey("idem-1").setEstimatedTokens(42).setMaxConcurrent(1)
                .setDeadline(Timestamp.newBuilder().setSeconds(Instant.now().plusSeconds(30).getEpochSecond()))
                .build();
    }

    private static final class RecordingObserver<T> implements io.grpc.stub.StreamObserver<T> {
        private final List<T> values = new ArrayList<>();
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) { values.add(value); }

        @Override
        public void onError(Throwable throwable) { error = throwable; }

        @Override
        public void onCompleted() { completed = true; }
    }
}
