package io.agentteams.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerCall.Listener;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;

class GrpcServerTracingInterceptorTest {

    @Test
    void extractsContextAndEndsSpanWhenStreamCompletes() {
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(builder.name(anyString())).thenReturn(builder);
        when(builder.start()).thenReturn(span);
        when(propagator.extract(any(), any())).thenReturn(builder);
        when(tracer.withSpan(span)).thenReturn(scope);
        ServerCall<Object, Object> call = mock(ServerCall.class);
        @SuppressWarnings("unchecked")
        ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
        @SuppressWarnings("unchecked")
        Listener<Object> listener = mock(Listener.class);
        when(next.startCall(any(), any())).thenReturn(listener);
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER),
                "00-4bf92f3577b34da6a3ce929d0e0e4736-1111111111111111-01");

        Listener<Object> traced = new GrpcServerTracingInterceptor(tracer, propagator)
                .interceptCall(call, headers, next);
        traced.onComplete();

        assertThat(traced).isNotNull();
        verify(propagator).extract(any(), any());
        verify(listener).onComplete();
        verify(span).end();
    }
}
