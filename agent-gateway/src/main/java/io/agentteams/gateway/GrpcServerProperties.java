package io.agentteams.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the gateway's standalone gRPC listener. */
@ConfigurationProperties(prefix = "agentteams.gateway.grpc")
public class GrpcServerProperties {

    private int port = 9090;
    private Duration shutdownTimeout = Duration.ofSeconds(10);

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("gRPC port must be between 0 and 65535");
        }
        this.port = port;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        if (shutdownTimeout == null || shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("gRPC shutdown timeout must be positive");
        }
        this.shutdownTimeout = shutdownTimeout;
    }
}
