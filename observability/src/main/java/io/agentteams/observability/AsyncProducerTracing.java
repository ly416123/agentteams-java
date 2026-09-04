package io.agentteams.observability;

import io.agentteams.application.api.TraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Restores the persisted parent and injects the producer span into an async envelope. */
public final class AsyncProducerTracing {
    private static final Propagator.Getter<Map<String, String>> GETTER = Map::get;
    private static final Propagator.Setter<Map<String, String>> SETTER = Map::put;

    private final Tracer tracer;
    private final Propagator propagator;

    public AsyncProducerTracing(Tracer tracer, Propagator propagator) {
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
        this.propagator = propagator == null ? Propagator.NOOP : propagator;
    }

    public static AsyncProducerTracing noop() {
        return new AsyncProducerTracing(Tracer.NOOP, Propagator.NOOP);
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
                        Map.of("traceparent", safe.traceparent(), "tracestate", safe.tracestate()), GETTER)
                        .name(name).start();
            }
        } catch (RuntimeException ignored) {
            // A malformed persisted context must not prevent the outbox from publishing.
            span = tracer.nextSpan().name(name).start();
        }
        return new Scope(span, tracer.withSpan(span), propagator);
    }

    public static final class Scope implements AutoCloseable {
        private final Span span;
        private final Tracer.SpanInScope spanInScope;
        private final Propagator propagator;
        private Throwable failure;

        private Scope(Span span, Tracer.SpanInScope spanInScope, Propagator propagator) {
            this.span = Objects.requireNonNull(span, "span");
            this.spanInScope = Objects.requireNonNull(spanInScope, "spanInScope");
            this.propagator = Objects.requireNonNull(propagator, "propagator");
        }

        public Scope tag(String key, String value) {
            if (key != null && value != null && !value.isBlank()) {
                span.tag(key, value);
            }
            return this;
        }

        public Map<String, String> inject(String fallbackTraceparent, String fallbackTracestate) {
            Map<String, String> carrier = new HashMap<>();
            if (fallbackTraceparent != null) {
                carrier.put("traceparent", fallbackTraceparent);
            }
            if (fallbackTracestate != null) {
                carrier.put("tracestate", fallbackTracestate);
            }
            try {
                propagator.inject(span.context(), carrier, SETTER);
            } catch (RuntimeException ignored) {
                // Keep the persisted context if injection is unavailable or malformed.
            }
            String traceparent = W3cSpanContext.traceparent(span.context());
            if (!traceparent.isBlank()) {
                carrier.put("traceparent", traceparent);
            }
            return carrier;
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
                    span.event("producer.error");
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
