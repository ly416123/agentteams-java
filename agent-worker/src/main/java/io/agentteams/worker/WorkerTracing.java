package io.agentteams.worker;

import io.agentteams.runtime.GrpcClientTracingInterceptor;
import io.agentteams.contracts.v1.EventMetadata;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.TracerProvider;
import java.util.Map;
import java.util.Objects;

/** Optional worker-side OTel bridge backed by the process-wide OpenTelemetry provider. */
final class WorkerTracing {
    private WorkerTracing() {
    }

    static Bridge create() {
        TracerProvider provider = GlobalOpenTelemetry.getTracerProvider();
        io.opentelemetry.api.trace.Tracer otelTracer = provider.get("agentteams-agent-worker");
        OtelCurrentTraceContext current = new OtelCurrentTraceContext();
        Tracer tracer = new OtelTracer(otelTracer, current, ignored -> { });
        Propagator propagator = new OtelPropagator(GlobalOpenTelemetry.getPropagators(), otelTracer);
        return new Bridge(tracer, propagator);
    }

    static final class Bridge {
        private static final Propagator.Getter<Map<String, String>> GETTER = Map::get;
        private final Tracer tracer;
        private final Propagator propagator;

        private Bridge(Tracer tracer, Propagator propagator) {
            this.tracer = tracer;
            this.propagator = propagator;
        }

        GrpcClientTracingInterceptor grpcClientInterceptor() {
            return new GrpcClientTracingInterceptor(tracer, propagator);
        }

        Scope start(String name, EventMetadata metadata) {
            Map<String, String> carrier = Map.of("traceparent", metadata == null ? "" : metadata.getTraceparent(),
                    "tracestate", metadata == null ? "" : metadata.getTracestate());
            Span span;
            try {
                span = propagator.extract(carrier, GETTER).name(name).start();
            } catch (RuntimeException ignored) {
                span = tracer.nextSpan().name(name).start();
            }
            return new Scope(span, tracer.withSpan(span));
        }
    }

    static final class Scope implements AutoCloseable {
        private final Span span;
        private final Tracer.SpanInScope spanInScope;

        private Scope(Span span, Tracer.SpanInScope spanInScope) {
            this.span = Objects.requireNonNull(span, "span");
            this.spanInScope = Objects.requireNonNull(spanInScope, "spanInScope");
        }

        Scope tag(String key, String value) {
            if (key != null && value != null && !value.isBlank()) {
                span.tag(key, value);
            }
            return this;
        }

        Scope error(Throwable error) {
            if (error != null) {
                span.error(error);
            }
            return this;
        }

        @Override
        public void close() {
            try {
                spanInScope.close();
            } finally {
                span.end();
            }
        }
    }
}
