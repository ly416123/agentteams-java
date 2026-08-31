package io.agentteams.controlplane.token;

public final class TokenLedgerConflictException extends RuntimeException {
    public TokenLedgerConflictException(String message) {
        super(message);
    }
}
