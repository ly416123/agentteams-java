package io.agentteams.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.TraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AsyncConsumerTracingTest {

    @Test
    void extractsW3cContextAndClosesConsumerSpan() {
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(propagator.extract(any(), any())).thenReturn(builder);
        when(tracer.withSpan(span)).thenReturn(scope);

        AsyncConsumerTracing tracing = new AsyncConsumerTracing(tracer, propagator);
        TraceContext context = new TraceContext("task-42",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "vendor=value");

        try (AsyncConsumerTracing.Scope ignored = tracing.start("agentteams.nats.execution.consume", context)) {
            // The span scope is intentionally active while the consumer callback runs.
        }

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, String>> carrier = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(propagator).extract(carrier.capture(), any());
        assertThat(carrier.getValue()).containsEntry("traceparent", context.traceparent())
                .containsEntry("tracestate", context.tracestate());
        verify(builder).name(eq("agentteams.nats.execution.consume"));
        verify(scope).close();
        verify(span).end();
    }
}
