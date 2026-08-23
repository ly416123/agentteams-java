package io.agentteams.controlplane.quota;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.application.api.QuotaReservationPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class InternalQuotaControllerTest {
    @Mock private QuotaReservationPort reservations;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalQuotaController(reservations, "secret"))
                .build();
    }

    @Test
    void rejectsRequestsWithoutInternalToken() throws Exception {
        mockMvc.perform(post("/internal/v1/quota/acquire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void delegatesAcquireAndReturnsStableDecision() throws Exception {
        when(reservations.acquire(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new QuotaReservationPort.AcquireDecision(true, "reservation-1", "", 0, ""));

        mockMvc.perform(post("/internal/v1/quota/acquire")
                        .header(InternalQuotaController.TOKEN_HEADER, "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"tenant-a","projectId":"project-a","idempotencyKey":"key-1",
                                 "estimatedTokens":10,"maxConcurrent":1,"deadline":"2030-01-01T00:00:00Z",
                                 "traceparent":"","tracestate":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.reservationId").value("reservation-1"));
    }
}
