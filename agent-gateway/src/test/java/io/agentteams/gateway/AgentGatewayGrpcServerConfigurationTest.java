package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.contracts.v1.AgentChannelGrpc;
import io.agentteams.contracts.v1.AgentMessage;
import io.agentteams.contracts.v1.ServerMessage;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = AgentGatewayApplication.class,
        properties = {
                "agentteams.gateway.grpc.port=0",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                "management.endpoint.health.group.readiness.include=readinessState"
        })
class AgentGatewayGrpcServerConfigurationTest {

    @Autowired
    private AgentGatewayGrpcServer grpcServer;

    @Autowired
    private AgentChannelService channelService;

    private ManagedChannel clientChannel;

    @AfterEach
    void closeClient() throws InterruptedException {
        if (clientChannel != null) {
            clientChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (grpcServer != null && grpcServer.isRunning()) {
            grpcServer.stop();
        }
        if (grpcServer != null) {
            assertThat(grpcServer.isRunning()).isFalse();
        }
    }

    @Test
    void startsNettyServerOnConfiguredPortAndRegistersAgentChannelService() throws Exception {
        assertThat(grpcServer.isRunning()).isTrue();
        assertThat(grpcServer.port()).isPositive();
        assertThat(channelService).isNotNull();

        clientChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcServer.port())
                .usePlaintext()
                .build();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        StreamObserver<AgentMessage> request = AgentChannelGrpc.newStub(clientChannel)
                .connect(new StreamObserver<>() {
                    @Override
                    public void onNext(ServerMessage message) {
                        if (message.hasReady() && message.getReady().getAccepted()) {
                            ready.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        streamError.set(throwable);
                    }

                    @Override
                    public void onCompleted() {
                        // The client closes the stream after observing Ready.
                    }
                });
        request.onNext(GatewayTestFixtures.hello("spring-wired-agent"));

        assertThat(ready.await(5, TimeUnit.SECONDS))
                .as("Ready response, stream error: %s", streamError.get())
                .isTrue();
        request.onCompleted();
    }
}
