package io.agentteams.gateway;

final class GatewayExceptions {

    private GatewayExceptions() {
    }

    static final class InvalidMessage extends RuntimeException {
        InvalidMessage(String message) {
            super(message);
        }
    }

    static final class StaleConnection extends RuntimeException {
        StaleConnection(String message) {
            super(message);
        }
    }

    static final class AuthenticationRejected extends RuntimeException {
        AuthenticationRejected(String message) {
            super(message);
        }
    }

    static final class ProtocolRejected extends RuntimeException {
        ProtocolRejected(String message) {
            super(message);
        }
    }
}
