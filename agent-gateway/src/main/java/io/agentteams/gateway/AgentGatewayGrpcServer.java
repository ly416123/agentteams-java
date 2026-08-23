package io.agentteams.gateway;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/** Owns the Netty gRPC listener and exposes a Spring-friendly lifecycle. */
public final class AgentGatewayGrpcServer implements AutoCloseable {

    private final int configuredPort;
    private final Duration shutdownTimeout;
    private final AgentChannelService channelService;
    private final GrpcTlsProperties tlsProperties;
    private final GrpcServerTracingInterceptor tracingInterceptor;
    private final QuotaServiceHandler quotaService;
    private volatile Server server;

    public AgentGatewayGrpcServer(int configuredPort, Duration shutdownTimeout,
            AgentChannelService channelService) {
        this(configuredPort, shutdownTimeout, channelService, new GrpcTlsProperties(),
                new GrpcServerTracingInterceptor(null, null), null);
    }

    public AgentGatewayGrpcServer(int configuredPort, Duration shutdownTimeout,
            AgentChannelService channelService, GrpcTlsProperties tlsProperties) {
        this(configuredPort, shutdownTimeout, channelService, tlsProperties,
                new GrpcServerTracingInterceptor(null, null), null);
    }

    public AgentGatewayGrpcServer(int configuredPort, Duration shutdownTimeout,
            AgentChannelService channelService, GrpcTlsProperties tlsProperties,
            GrpcServerTracingInterceptor tracingInterceptor) {
        this(configuredPort, shutdownTimeout, channelService, tlsProperties, tracingInterceptor, null);
    }

    public AgentGatewayGrpcServer(int configuredPort, Duration shutdownTimeout,
            AgentChannelService channelService, GrpcTlsProperties tlsProperties,
            GrpcServerTracingInterceptor tracingInterceptor, QuotaServiceHandler quotaService) {
        if (configuredPort < 0 || configuredPort > 65_535) {
            throw new IllegalArgumentException("gRPC port must be between 0 and 65535");
        }
        this.configuredPort = configuredPort;
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("gRPC shutdown timeout must be positive");
        }
        this.channelService = Objects.requireNonNull(channelService, "channelService");
        this.tlsProperties = Objects.requireNonNull(tlsProperties, "tlsProperties");
        this.tracingInterceptor = Objects.requireNonNull(tracingInterceptor, "tracingInterceptor");
        this.quotaService = quotaService;
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        tlsProperties.validate();
        NettyServerBuilder builder = NettyServerBuilder.forPort(configuredPort)
                .intercept(new GrpcTransportIdentity.Interceptor())
                .intercept(tracingInterceptor)
                .addService(channelService);
        if (quotaService != null) {
            builder.addService(quotaService);
        }
        if (tlsProperties.isEnabled()) {
            SslContext sslContext = GrpcSslContexts.forServer(
                            new File(tlsProperties.getCertificateChain()),
                            new File(tlsProperties.getPrivateKey()))
                    .trustManager(new File(tlsProperties.getTrustCertificateCollection()))
                    .clientAuth(ClientAuth.REQUIRE)
                    .build();
            builder.sslContext(sslContext);
        }
        Server started = builder.build()
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
