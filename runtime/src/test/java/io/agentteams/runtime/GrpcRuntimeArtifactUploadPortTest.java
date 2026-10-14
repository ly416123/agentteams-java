package io.agentteams.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.agentteams.contracts.v1.CompleteArtifactUploadResponse;
import io.agentteams.contracts.v1.PrepareArtifactUploadResponse;
import io.agentteams.contracts.v1.TaskArtifactServiceGrpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GrpcRuntimeArtifactUploadPortTest {
    @Test
    void sendsGatewayCredentialOnBothArtifactRpcCalls() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        Metadata.Key<String> tokenKey = Metadata.Key.of("x-agent-token", Metadata.ASCII_STRING_MARSHALLER);
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                token.set(headers.get(tokenKey));
                return next.startCall(call, headers);
            }
        };
        HttpServer objectStore = HttpServer.create(new InetSocketAddress(0), 0);
        objectStore.createContext("/put", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        objectStore.start();

        String serverName = InProcessServerBuilder.generateName();
        Server gateway = InProcessServerBuilder.forName(serverName).directExecutor().intercept(capture)
                .addService(new TaskArtifactServiceGrpc.TaskArtifactServiceImplBase() {
                    @Override
                    public void prepareTaskArtifactUpload(io.agentteams.contracts.v1.PrepareArtifactUploadRequest request,
                            StreamObserver<PrepareArtifactUploadResponse> observer) {
                        observer.onNext(PrepareArtifactUploadResponse.newBuilder().setAccepted(true)
                                .setStorageKey("tasks/task/artifacts/output.md")
                                .setUploadUrl("http://127.0.0.1:" + objectStore.getAddress().getPort() + "/put")
                                .setDownloadUrl("http://download/output.md").build());
                        observer.onCompleted();
                    }

                    @Override
                    public void completeTaskArtifactUpload(io.agentteams.contracts.v1.CompleteArtifactUploadRequest request,
                            StreamObserver<CompleteArtifactUploadResponse> observer) {
                        observer.onNext(CompleteArtifactUploadResponse.newBuilder().setAccepted(true)
                                .setArtifactId("artifact-1").setStorageKey(request.getStorageKey()).build());
                        observer.onCompleted();
                    }
                }).build().start();
        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        try {
            var port = new GrpcRuntimeArtifactUploadPort(channel, "worker-1", Clock.systemUTC(),
                    Duration.ofSeconds(2), () -> "worker-gateway-token", () -> "");

            assertThat(port.upload("task", "attempt", "output.md", "text/markdown; charset=UTF-8",
                    "hello".getBytes())).isEqualTo(new RuntimeArtifactUploadPort.UploadedArtifact(
                            "tasks/task/artifacts/output.md", "http://download/output.md"));
            assertThat(token).hasValue("worker-gateway-token");
        } finally {
            channel.shutdownNow();
            gateway.shutdownNow();
            objectStore.stop(0);
        }
    }
}
