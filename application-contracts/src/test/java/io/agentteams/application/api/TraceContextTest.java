package io.agentteams.application.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TraceContextTest {
    @Test
    void acceptsBoundedW3cContextAndNormalizesTraceparent() {
        TraceContext context = new TraceContext("http-request-1",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "vendor=value");

        assertEquals("http-request-1", context.correlationId());
        org.junit.jupiter.api.Assertions.assertTrue(context.traceparent()
                .startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736"));
        org.junit.jupiter.api.Assertions.assertTrue(context.present());
    }

    @Test
    void rejectsMalformedOrUnboundedContext() {
        assertThrows(IllegalArgumentException.class, () -> new TraceContext("bad value", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new TraceContext("ok", "not-a-traceparent", ""));
    }
}
