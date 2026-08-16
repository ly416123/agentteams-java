package io.agentteams.controlplane.matrix;

import java.time.Clock;
import java.util.Objects;
import java.util.function.BiFunction;

public final class MatrixAppService {
    private final MatrixInboxRepository inbox;
    private final MatrixCommandParser parser;
    private final Clock clock;

    public MatrixAppService(MatrixInboxRepository inbox) {
        this(inbox, new MatrixCommandParser(), Clock.systemUTC());
    }

    MatrixAppService(MatrixInboxRepository inbox, MatrixCommandParser parser, Clock clock) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    boolean beginTransaction(String transactionId) {
        requireText(transactionId, "transactionId");
        return inbox.claimTransaction(transactionId, clock.instant());
    }

    void completeTransaction(String transactionId) {
        requireText(transactionId, "transactionId");
        inbox.completeTransaction(transactionId, clock.instant());
    }

    public Result receive(String transactionId, String eventId, String roomId, String sender, String body,
            BiFunction<String, MatrixCommand, String> commandHandler) {
        requireText(transactionId, "transactionId");
        requireText(eventId, "eventId");
        requireText(roomId, "roomId");
        requireText(sender, "sender");
        Objects.requireNonNull(commandHandler, "commandHandler");
        if (!inbox.claim(transactionId, eventId, roomId, sender, body, clock.instant())) {
            return new Result(false, null);
        }
        if (!parser.isCommand(body)) {
            return new Result(true, null);
        }
        MatrixCommand command = parser.parse(body);
        return new Result(true, commandHandler.apply(sender, command));
    }

    public record Result(boolean accepted, String response) { }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
