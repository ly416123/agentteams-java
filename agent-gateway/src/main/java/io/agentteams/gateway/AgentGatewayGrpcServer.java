package io.agentteams.gateway;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/** Owns the Netty gRPC listener and exposes a Spring-friendly lifecycle. */
public final class AgentGatewayGrpcServer implements AutoCloseable {

    private final int configuredPort;
    private final Duration shutdownTimeout;
    private final AgentChannelService channelService;
    private volatile Server server;

    public AgentGatewayGrpcServer(int configuredPort, Duration shutdownTimeout,
            AgentChannelService channelService) {
        if (configuredPort < 0 || configuredPort > 65_535) {
            throw new IllegalArgumentException("gRPC port must be between 0 and 65535");
        }
        this.configuredPort = configuredPort;
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("gRPC shutdown timeout must be positive");
        }
        this.channelService = Objects.requireNonNull(channelService, "channelService");
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        Server started = NettyServerBuilder.forPort(configuredPort)
                .intercept(new GrpcTransportIdentity.Interceptor())
                .addService(channelService)
                .build()
                .start();
        server = started;
    }

    public boolean isRunning() {
        Server current = server;
        return current != null && !current.isShutdown() && !current.isTerminated();
    }

    public int port() {
        Server current = server;
        if (current == null) {
            throw new IllegalStateException("gRPC server has not started");
        }
        return current.getPort();
    }

    public synchronized void stop() {
        Server current = server;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(shutdownTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                current.shutdownNow();
                current.awaitTermination(shutdownTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            server = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
