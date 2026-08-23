package io.agentteams.controlplane.observability;

import io.agentteams.application.api.TraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Objects;

/** Restores W3C context and creates a bounded span around asynchronous message handling. */
public final class AsyncConsumerTracing {
    private final Tracer tracer;
    private final Propagator propagator;

    public AsyncConsumerTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
        this.propagator = propagator == null ? Propagator.NOOP : propagator;
    }

    public static AsyncConsumerTracing noop() {
        return new AsyncConsumerTracing(Tracer.NOOP, Propagator.NOOP);
    }

    public Scope start(String name, TraceContext context) {
        Objects.requireNonNull(name, "name");
        TraceContext safe = context == null ? TraceContext.empty() : context;
        Span span;
        try {
            Span.Builder builder = W3cSpanContext.child(tracer, safe.traceparent(), name);
            if (builder != null) {
                span = builder.start();
            } else {
                span = propagator.extract(
                        java.util.Map.of("traceparent", safe.traceparent(), "tracestate", safe.tracestate()),
                        java.util.Map::get).name(name).start();
            }
        } catch (RuntimeException ignored) {
            // A malformed or unsupported carrier must never block an at-least-once consumer.
            span = tracer.nextSpan().name(name).start();
        }
        return new Scope(span, tracer.withSpan(span));
    }

    public static final class Scope implements AutoCloseable {
        private final Span span;
        private final Tracer.SpanInScope spanInScope;
        private Throwable failure;

        private Scope(Span span, Tracer.SpanInScope spanInScope) {
            this.span = Objects.requireNonNull(span, "span");
            this.spanInScope = Objects.requireNonNull(spanInScope, "spanInScope");
        }

        public Scope tag(String key, String value) {
            if (key != null && value != null && !value.isBlank()) {
                span.tag(key, value);
            }
            return this;
        }

        public Scope error(Throwable error) {
            failure = error;
            if (error != null) {
                span.error(error);
            }
            return this;
        }

        @Override
        public void close() {
            try {
                if (failure != null) {
                    span.event("consumer.error");
                }
            } finally {
                try {
                    spanInScope.close();
                } finally {
                    span.end();
                }
            }
        }
    }
}
