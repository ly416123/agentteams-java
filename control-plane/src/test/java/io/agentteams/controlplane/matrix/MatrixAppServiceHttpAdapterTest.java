package io.agentteams.controlplane.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class MatrixAppServiceHttpAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JdbcTemplate jdbc;
    private MatrixAppServiceHttpAdapter adapter;
    private AtomicInteger handled;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        handled = new AtomicInteger();
        MatrixAppService appService = new MatrixAppService(new MatrixInboxRepository(jdbc));
        adapter = new MatrixAppServiceHttpAdapter(appService, (sender, command) -> {
            handled.incrementAndGet();
            return "accepted";
        });
    }

    @Test
    void acceptsMultipleEventsAndDispatchesEachCommand() {
        MatrixAppServiceHttpAdapter.TransactionRequest request = new MatrixAppServiceHttpAdapter.TransactionRequest(List.of(
                event("$event-1", "!agentteams start first"),
                event("$event-2", "/status " + java.util.UUID.randomUUID())));

        var response = adapter.receive("transaction-1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accepted()).isTrue();
        assertThat(response.getBody().events()).extracting(MatrixAppServiceHttpAdapter.EventResult::status)
                .containsExactly(MatrixAppServiceHttpAdapter.EventStatus.HANDLED,
                        MatrixAppServiceHttpAdapter.EventStatus.HANDLED);
        assertThat(handled).hasValue(2);
    }

    @Test
    void duplicateTransactionDoesNotDispatchAgain() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        var response = adapter.receive("transaction-duplicate", new MatrixAppServiceHttpAdapter.TransactionRequest(
                List.of(event("$event-1", "!agentteams start once"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().duplicate()).isTrue();
        assertThat(handled).hasValue(0);
    }

    @Test
    void ignoresNonCommandMessagesAfterClaimingEvent() {
        var response = adapter.receive("transaction-ignored", new MatrixAppServiceHttpAdapter.TransactionRequest(
                List.of(event("$event-1", "hello team"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().events().get(0).status())
                .isEqualTo(MatrixAppServiceHttpAdapter.EventStatus.IGNORED);
        assertThat(handled).hasValue(0);
    }

    @Test
    void rejectsMissingTransactionOrMessageFieldsWithSafeError() {
        var missingTransaction = adapter.receive(" ", new MatrixAppServiceHttpAdapter.TransactionRequest(
                List.of(event("$event-1", "!agentteams start invalid"))));
        var missingBody = adapter.receive("transaction-invalid", new MatrixAppServiceHttpAdapter.TransactionRequest(
                List.of(new MatrixAppServiceHttpAdapter.EventRequest(
                        "$event-2", "m.room.message", "!room:example.org", "@alice:example.org",
                        MAPPER.createObjectNode()))));

        assertThat(missingTransaction.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingTransaction.getBody()).isEqualTo(MatrixAppServiceHttpAdapter.HttpResponse.error(
                null, "INVALID_REQUEST", "invalid Matrix AppService transaction"));
        assertThat(missingBody.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingBody.getBody()).isEqualTo(MatrixAppServiceHttpAdapter.HttpResponse.error(
                "transaction-invalid", "INVALID_REQUEST", "invalid Matrix AppService transaction"));
    }

    @Test
    void mapsHandlerFailureWithoutExposingExceptionDetails() {
        MatrixAppService appService = new MatrixAppService(new MatrixInboxRepository(jdbc));
        adapter = new MatrixAppServiceHttpAdapter(appService, (sender, command) -> {
            throw new IllegalStateException("secret database details");
        });

        var response = adapter.receive("transaction-handler-error", new MatrixAppServiceHttpAdapter.TransactionRequest(
                List.of(event("$event-1", "!agentteams start failure"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(MatrixAppServiceHttpAdapter.HttpResponse.error(
                "transaction-handler-error", "HANDLER_ERROR", "command could not be processed"));
        assertThat(response.getBody().message()).doesNotContain("secret");
    }

    @Test
    void supportsStandardMatrixEventTypeAndRejectsMissingRequiredEventId() {
        var invalid = adapter.receive("transaction-event-invalid", new MatrixAppServiceHttpAdapter.TransactionRequest(
                List.of(new MatrixAppServiceHttpAdapter.EventRequest(
                        "", "m.room.message", "!room:example.org", "@alice:example.org", text("!agentteams start x")))));

        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(jdbc);
    }

    @Test
    void returnsServiceUnavailableForInboxFailure() {
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));

        var response = adapter.receive("transaction-db-error", new MatrixAppServiceHttpAdapter.TransactionRequest(
                List.of(event("$event-1", "!agentteams start failure"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(MatrixAppServiceHttpAdapter.HttpResponse.error(
                "transaction-db-error", "DEPENDENCY_UNAVAILABLE", "Matrix inbox is unavailable"));
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void bindsSenderBeforeCallingIdentityHandlerWhenBinderIsConfigured() {
        Principal principal = new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                java.util.Set.of("task:read"));
        MatrixIdentity bound = new MatrixIdentity("@alice:example.org", principal);
        MatrixIdentityBinder binder = sender -> java.util.Optional.of(bound);
        AtomicReference<MatrixIdentity> received = new AtomicReference<>();
        MatrixCommandHandler handler = new MatrixCommandHandler() {
            @Override
            public String handle(String sender, MatrixCommand command) {
                return "legacy";
            }

            @Override
            public String handle(MatrixIdentity identity, MatrixCommand command) {
                received.set(identity);
                return "bound";
            }
        };
        adapter = new MatrixAppServiceHttpAdapter(new MatrixAppService(new MatrixInboxRepository(jdbc)), handler, binder);

        var response = adapter.receive("transaction-bound", new MatrixAppServiceHttpAdapter.TransactionRequest(
                List.of(event("$event-bound", "!agentteams start identity"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().events().get(0).response()).isEqualTo("bound");
        assertThat(received).hasValue(bound);
    }

    private static MatrixAppServiceHttpAdapter.EventRequest event(String eventId, String body) {
        return new MatrixAppServiceHttpAdapter.EventRequest(
                eventId, "m.room.message", "!room:example.org", "@alice:example.org", text(body));
    }

    private static JsonNode text(String body) {
        return MAPPER.createObjectNode().put("msgtype", "m.text").put("body", body);
    }
}
