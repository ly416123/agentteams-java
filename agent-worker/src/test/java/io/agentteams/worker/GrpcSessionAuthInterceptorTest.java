package io.agentteams.worker;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.contracts.v1.AgentChannelGrpc;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.ServerMessage;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GrpcSessionAuthInterceptorTest {
    @Test
    void workerGatewayChannelAttachesItsEnrolledSessionToken() throws Exception {
        String token = "ats_enrolled-worker-session-token";
        AtomicReference<String> authorization = new AtomicReference<>();
        CountDownLatch callObserved = new CountDownLatch(1);
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                authorization.set(headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)));
                callObserved.countDown();
                return next.startCall(call, headers);
            }
        };
        Server server = ServerBuilder.forPort(0)
                .addService(new AgentChannelGrpc.AgentChannelImplBase() {
                    @Override
                    public StreamObserver<AgentMessage> connect(StreamObserver<ServerMessage> responseObserver) {
                        return new StreamObserver<>() {
                            @Override public void onNext(AgentMessage value) { }
                            @Override public void onError(Throwable error) { }
                            @Override public void onCompleted() { responseObserver.onCompleted(); }
                        };
                    }
                })
                .intercept(capture)
                .build()
                .start();
        var configuration = AgentTeamsWorker.WorkerConfiguration.from(Map.of(
                "AGENTTEAMS_AGENT_ID", UUID.randomUUID().toString(),
                "AGENTTEAMS_GATEWAY_HOST", "127.0.0.1",
                "AGENTTEAMS_GATEWAY_PORT", Integer.toString(server.getPort()),
                "AGENTTEAMS_GATEWAY_AUTH_TOKEN", token));
        var channel = AgentTeamsWorker.gatewayChannel(configuration,
                io.agentteams.runtime.GrpcClientTracingInterceptor.noop());
        try {
            AgentChannelGrpc.newStub(channel).connect(new StreamObserver<>() {
                @Override public void onNext(ServerMessage value) { }
                @Override public void onError(Throwable error) { }
                @Override public void onCompleted() { }
            });

            assertThat(callObserved.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(authorization).hasValue("Bearer " + token);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

}
