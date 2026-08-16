package io.agentteams.gateway;

import io.grpc.Status;

/** Seam used to close a superseded stream without coupling the registry to a gRPC server implementation. */
@FunctionalInterface
public interface ConnectionTermination {

    void terminate(AgentConnection connection, Termination termination);

    static ConnectionTermination grpcStream() {
        return (connection, termination) -> connection.outbound().onError(
                Status.FAILED_PRECONDITION.withDescription(termination.message()).asRuntimeException());
    }

    record Termination(String code, String message, boolean retryable) {
        public Termination {
            if (code == null || code.isBlank() || message == null || message.isBlank()) {
                throw new IllegalArgumentException("termination code and message are required");
            }
        }

        public static Termination stale() {
            return new Termination("STALE_CONNECTION", "connection was superseded by a newer connection", false);
        }
    }
}
