package io.agentteams.runtime;

import io.agentteams.contracts.v1.AgentChannelGrpc;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.ServerMessage;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcAgentChannelPortTest {
    @Test
    void bridgesBidirectionalGrpcStreamToAgentChannelPort() throws Exception {
        RecordingService service = new RecordingService();
        Server server = NettyServerBuilder.forPort(0).addService(service).build().start();
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort())
                .usePlaintext().build();
        List<ServerMessage> received = new ArrayList<>();
        CountDownLatch serverMessage = new CountDownLatch(1);
        GrpcAgentChannelPort port = new GrpcAgentChannelPort(channel, message -> {
            received.add(message);
            serverMessage.countDown();
        });

        port.connect();
        port.send(AgentMessage.newBuilder().setError(
                io.agentteams.contracts.v1.Error.newBuilder().setCode("test")).build());

        assertThat(service.clientMessage.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(serverMessage.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(service.messages).hasSize(1);
        assertThat(received).extracting(ServerMessage::getPayloadCase)
                .containsExactly(ServerMessage.PayloadCase.ERROR);

        port.close();
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static final class RecordingService extends AgentChannelGrpc.AgentChannelImplBase {
        private final CountDownLatch clientMessage = new CountDownLatch(1);
        private final List<AgentMessage> messages = new ArrayList<>();

        @Override
        public StreamObserver<AgentMessage> connect(StreamObserver<ServerMessage> responseObserver) {
            responseObserver.onNext(ServerMessage.newBuilder().setError(
                    io.agentteams.contracts.v1.Error.newBuilder().setCode("ready")).build());
            return new StreamObserver<>() {
                @Override
                public void onNext(AgentMessage value) {
                    messages.add(value);
                    clientMessage.countDown();
                }

                @Override
                public void onError(Throwable throwable) { }

                @Override
                public void onCompleted() { responseObserver.onCompleted(); }
            };
        }
    }
}
