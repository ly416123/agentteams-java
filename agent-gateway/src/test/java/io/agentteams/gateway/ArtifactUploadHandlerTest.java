package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import io.agentteams.application.api.ArtifactUploadHttp;
import io.agentteams.contracts.v1.ArtifactProtocolError;
import io.agentteams.contracts.v1.CompleteArtifactUploadRequest;
import io.agentteams.contracts.v1.CompleteArtifactUploadResponse;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.PrepareArtifactUploadRequest;
import io.agentteams.contracts.v1.PrepareArtifactUploadResponse;
import io.agentteams.contracts.v1.ProtocolVersion;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ArtifactUploadHandlerTest {
    private static final String TASK_ID = UUID.randomUUID().toString();
    private static final String ATTEMPT_ID = UUID.randomUUID().toString();

    /** 现有用例默认持有传输凭证；空白凭证场景由专门的拒绝用例覆盖。 */
    private static final Supplier<String> CREDENTIAL = () -> "worker-1";

    @Test
    void mapsPrepareToBridgeAndReturnsPresignedTicket() {
        RecordingBridge bridge = new RecordingBridge();
        ArtifactUploadHandler handler = new ArtifactUploadHandler(bridge, Clock.systemUTC(), CREDENTIAL);
        RecordingObserver<PrepareArtifactUploadResponse> observer = new RecordingObserver<>();

        handler.prepareTaskArtifactUpload(prepareRequest(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.values).singleElement().satisfies(response -> {
            assertThat(response.getAccepted()).isTrue();
            assertThat(response.getStorageKey()).isEqualTo("tasks/" + TASK_ID + "/attempts/" + ATTEMPT_ID
                    + "/artifacts/plan.md");
            assertThat(response.getUploadUrl()).isEqualTo("http://minio/put");
            assertThat(response.getDownloadUrl()).isEqualTo("http://minio/get");
        });
        assertThat(bridge.prepares).singleElement().satisfies(call -> {
            assertThat(call.taskId()).isEqualTo(TASK_ID);
            assertThat(call.attemptId()).isEqualTo(ATTEMPT_ID);
            assertThat(call.agentId()).isEqualTo("worker-1");
            assertThat(call.name()).isEqualTo("plan.md");
            assertThat(call.contentType()).isEqualTo("text/markdown");
            // Without a deadline the presign window stays at the default.
            assertThat(call.expirySeconds()).isEqualTo(900);
        });
    }

    @Test
    void clampsThePresignWindowToTheWorkerDeadline() {
        RecordingBridge bridge = new RecordingBridge();
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        ArtifactUploadHandler handler = new ArtifactUploadHandler(bridge, clock, CREDENTIAL);
        RecordingObserver<PrepareArtifactUploadResponse> observer = new RecordingObserver<>();
        PrepareArtifactUploadRequest request = PrepareArtifactUploadRequest.newBuilder()
                .mergeFrom(prepareRequest())
                .setDeadline(Timestamp.newBuilder().setSeconds(7200))
                .build();

        handler.prepareTaskArtifactUpload(request, observer);

        assertThat(bridge.prepares).singleElement()
                .satisfies(call -> assertThat(call.expirySeconds()).isEqualTo(3600));
    }

    @Test
    void rejectsMalformedPrepareWithoutCallingTheBridge() {
        RecordingBridge bridge = new RecordingBridge();
        ArtifactUploadHandler handler = new ArtifactUploadHandler(bridge, Clock.systemUTC(), CREDENTIAL);
        RecordingObserver<PrepareArtifactUploadResponse> observer = new RecordingObserver<>();

        handler.prepareTaskArtifactUpload(PrepareArtifactUploadRequest.newBuilder()
                .setProtocolVersion(ProtocolVersion.newBuilder().setMajor(2).setMinor(3)).build(), observer);

        assertThat(observer.completed).isTrue();
        assertThat(observer.values).singleElement().satisfies(response -> {
            assertThat(response.getAccepted()).isFalse();
            assertThat(response.getProtocolError())
                    .isEqualTo(ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_INVALID_ARGUMENT);
        });
        assertThat(bridge.prepares).isEmpty();
    }

    @Test
    void mapsAttemptMismatchFromTheControlPlaneStatus() {
        RecordingBridge bridge = new RecordingBridge();
        bridge.prepareError = new ControlPlaneArtifactUploadClient.ControlPlaneInvocationException(
                "Control Plane artifact endpoint returned HTTP 403", 403);
        ArtifactUploadHandler handler = new ArtifactUploadHandler(bridge, Clock.systemUTC(), CREDENTIAL);
        RecordingObserver<PrepareArtifactUploadResponse> observer = new RecordingObserver<>();

        handler.prepareTaskArtifactUpload(prepareRequest(), observer);

        assertThat(observer.values).singleElement().satisfies(response -> {
            assertThat(response.getAccepted()).isFalse();
            assertThat(response.getProtocolError())
                    .isEqualTo(ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_ATTEMPT_MISMATCH);
        });
    }

    @Test
    void mapsCompleteToBridgeAndReturnsArtifactId() {
        RecordingBridge bridge = new RecordingBridge();
        ArtifactUploadHandler handler = new ArtifactUploadHandler(bridge, Clock.systemUTC(), CREDENTIAL);
        RecordingObserver<CompleteArtifactUploadResponse> observer = new RecordingObserver<>();

        handler.completeTaskArtifactUpload(completeRequest(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.values).singleElement().satisfies(response -> {
            assertThat(response.getAccepted()).isTrue();
            assertThat(response.getArtifactId()).isEqualTo("artifact-1");
            assertThat(response.getStorageKey()).isEqualTo("tasks/" + TASK_ID + "/attempts/" + ATTEMPT_ID
                    + "/artifacts/plan.md");
        });
        assertThat(bridge.completes).singleElement().satisfies(call -> {
            assertThat(call.sha256()).isEqualTo("abc");
            assertThat(call.sizeBytes()).isEqualTo(9);
            assertThat(call.storageKey()).isEqualTo("tasks/" + TASK_ID + "/attempts/" + ATTEMPT_ID
                    + "/artifacts/plan.md");
        });
    }

    @Test
    void rejectsMalformedCompleteWithoutCallingTheBridge() {
        RecordingBridge bridge = new RecordingBridge();
        ArtifactUploadHandler handler = new ArtifactUploadHandler(bridge, Clock.systemUTC(), CREDENTIAL);
        RecordingObserver<CompleteArtifactUploadResponse> observer = new RecordingObserver<>();

        handler.completeTaskArtifactUpload(CompleteArtifactUploadRequest.newBuilder()
                .setProtocolVersion(ProtocolVersion.newBuilder().setMajor(2).setMinor(3)).build(), observer);

        assertThat(observer.values).singleElement().satisfies(response -> {
            assertThat(response.getAccepted()).isFalse();
            assertThat(response.getProtocolError())
                    .isEqualTo(ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_INVALID_ARGUMENT);
        });
        assertThat(bridge.completes).isEmpty();
    }

    @Test
    void rejectsPrepareWithoutTransportCredential() {
        RecordingBridge bridge = new RecordingBridge();
        // 公理二：无传输凭证（x-agent-token/Bearer）的 unary 调用直接拒绝，
        // 不触碰 Control Plane。凭证有效性由未来的认证端口校验。
        ArtifactUploadHandler handler = new ArtifactUploadHandler(bridge, Clock.systemUTC(), () -> "");
        RecordingObserver<PrepareArtifactUploadResponse> observer = new RecordingObserver<>();

        handler.prepareTaskArtifactUpload(prepareRequest(), observer);

        assertThat(observer.values).singleElement().satisfies(response -> {
            assertThat(response.getAccepted()).isFalse();
            assertThat(response.getProtocolError())
                    .isEqualTo(ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_UNAUTHENTICATED);
        });
        assertThat(bridge.prepares).isEmpty();
    }

    @Test
    void rejectsCompleteWithoutTransportCredential() {
        RecordingBridge bridge = new RecordingBridge();
        ArtifactUploadHandler handler = new ArtifactUploadHandler(bridge, Clock.systemUTC(), () -> "");
        RecordingObserver<CompleteArtifactUploadResponse> observer = new RecordingObserver<>();

        handler.completeTaskArtifactUpload(completeRequest(), observer);

        assertThat(observer.values).singleElement().satisfies(response -> {
            assertThat(response.getAccepted()).isFalse();
            assertThat(response.getProtocolError())
                    .isEqualTo(ArtifactProtocolError.ARTIFACT_PROTOCOL_ERROR_UNAUTHENTICATED);
        });
        assertThat(bridge.completes).isEmpty();
    }

    private static PrepareArtifactUploadRequest prepareRequest() {
        return PrepareArtifactUploadRequest.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setAgentId("worker-1").setTaskId(TASK_ID).setAttemptId(ATTEMPT_ID)
                        .setLeaseId(UUID.randomUUID().toString()))
                .setProtocolVersion(ProtocolVersion.newBuilder().setMajor(2).setMinor(3))
                .setTaskId(TASK_ID).setAttemptId(ATTEMPT_ID).setAgentId("worker-1")
                .setName("plan.md").setContentType("text/markdown")
                .setSizeBytes(9)
                .build();
    }

    private static CompleteArtifactUploadRequest completeRequest() {
        return CompleteArtifactUploadRequest.newBuilder()
                .setMetadata(EventMetadata.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setAgentId("worker-1").setTaskId(TASK_ID).setAttemptId(ATTEMPT_ID)
                        .setLeaseId(UUID.randomUUID().toString()))
                .setProtocolVersion(ProtocolVersion.newBuilder().setMajor(2).setMinor(3))
                .setTaskId(TASK_ID).setAttemptId(ATTEMPT_ID).setAgentId("worker-1")
                .setName("plan.md").setContentType("text/markdown")
                .setStorageKey("tasks/" + TASK_ID + "/attempts/" + ATTEMPT_ID + "/artifacts/plan.md")
                .setSizeBytes(9).setSha256("abc")
                .build();
    }

    private static final class RecordingBridge implements ArtifactUploadBridge {
        final List<ArtifactUploadHttp.PrepareRequest> prepares = new ArrayList<>();
        final List<ArtifactUploadHttp.CompleteRequest> completes = new ArrayList<>();
        RuntimeException prepareError;

        @Override
        public ArtifactUploadHttp.PrepareResponse prepare(ArtifactUploadHttp.PrepareRequest request) {
            prepares.add(request);
            if (prepareError != null) {
                throw prepareError;
            }
            return new ArtifactUploadHttp.PrepareResponse(
                    "tasks/" + TASK_ID + "/attempts/" + ATTEMPT_ID + "/artifacts/plan.md",
                    "http://minio/put", "http://minio/get");
        }

        @Override
        public ArtifactUploadHttp.CompleteResponse complete(ArtifactUploadHttp.CompleteRequest request) {
            completes.add(request);
            return new ArtifactUploadHttp.CompleteResponse("artifact-1",
                    "tasks/" + TASK_ID + "/attempts/" + ATTEMPT_ID + "/artifacts/plan.md");
        }
    }

    private static final class RecordingObserver<T> implements StreamObserver<T> {
        final List<T> values = new ArrayList<>();
        Throwable error;
        boolean completed;

        @Override
        public void onNext(T value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
