package io.agentteams.controlplane.token;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class InternalTokenLedgerControllerTest {
    private static final TokenLedgerScope SCOPE = new TokenLedgerScope("org-1", "tenant-1", "project-1");
    private static final UUID RESERVATION_ID = UUID.randomUUID();

    @Test
    void protectsSettlementEndpointAndForwardsTheTenantScope() throws Exception {
        TokenLedgerService ledger = org.mockito.Mockito.mock(TokenLedgerService.class);
        TokenReservation reservation = new TokenReservation(RESERVATION_ID, SCOPE, null, null, 100, 73,
                TokenReservation.State.SETTLED, "reserve-1", "settle-1", null, Instant.now(), Instant.now());
        when(ledger.settle(eq(SCOPE), eq(RESERVATION_ID), eq(73L), eq("settle-1"), eq("worker"), eq("model-a"), any()))
                .thenReturn(reservation);
        MockMvc mvc = standaloneSetup(new InternalTokenLedgerController(ledger, "internal-secret", true)).build();

        mvc.perform(post("/internal/v1/token-ledger/{id}/settle", RESERVATION_ID)
                        .header(InternalTokenLedgerController.TOKEN_HEADER, "internal-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"org-1\",\"tenantId\":\"tenant-1\",\"projectId\":\"project-1\",\"actualTokens\":73,\"source\":\"worker\",\"model\":\"model-a\",\"idempotencyKey\":\"settle-1\"}"))
                .andExpect(status().isOk());
        verify(ledger).settle(eq(SCOPE), eq(RESERVATION_ID), eq(73L), eq("settle-1"), eq("worker"), eq("model-a"), any());
    }

    @Test
    void rejectsMissingInternalToken() throws Exception {
        MockMvc mvc = standaloneSetup(new InternalTokenLedgerController(org.mockito.Mockito.mock(TokenLedgerService.class),
                "internal-secret", true)).build();

        mvc.perform(post("/internal/v1/token-ledger/reserve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":\"org-1\",\"tenantId\":\"tenant-1\",\"estimatedTokens\":1}"))
                .andExpect(status().isForbidden());
    }
}
