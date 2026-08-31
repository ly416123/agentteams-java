package io.agentteams.controlplane.token;

public final class TokenLedgerNotFoundException extends RuntimeException {
    public TokenLedgerNotFoundException() {
        super("token reservation was not found");
    }
}
