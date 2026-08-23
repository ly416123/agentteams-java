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
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.api.trace.TracerProvider;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Optional worker-side OTel bridge backed by the process-wide OpenTelemetry provider. */
final class WorkerTracing {
    private static final String DEFAULT_SERVICE_NAME = "agentteams-agent-worker";
    private static final String ENABLED = "AGENTTEAMS_OBSERVABILITY_TRACING_ENABLED";
    private static final String SAMPLING_PROBABILITY =
            "AGENTTEAMS_OBSERVABILITY_TRACING_SAMPLING_PROBABILITY";
    private static final String OTLP_ENDPOINT = "AGENTTEAMS_OBSERVABILITY_OTLP_TRACING_ENDPOINT";
    private static final String SERVICE_NAME = "AGENTTEAMS_OBSERVABILITY_SERVICE_NAME";

    private WorkerTracing() {
    }

    static Bridge create() {
        return create(System.getenv());
    }

    static Bridge create(Map<String, String> environment) {
        Configuration configuration = Configuration.from(environment);
        OpenTelemetrySdk sdk = configuration.enabled() && !configuration.otlpEndpoint().isBlank()
                ? registerSdk(configuration)
                : null;
        if (configuration.enabled() && configuration.otlpEndpoint().isBlank()) {
            System.err.println("Worker OTel tracing enabled but no OTLP endpoint is configured; tracing remains NOOP");
        }
        OpenTelemetry openTelemetry = GlobalOpenTelemetry.get();
        TracerProvider provider = openTelemetry.getTracerProvider();
        io.opentelemetry.api.trace.Tracer otelTracer = provider.get("agentteams-agent-worker");
        OtelCurrentTraceContext current = new OtelCurrentTraceContext();
        Tracer tracer = new OtelTracer(otelTracer, current, ignored -> { });
        Propagator propagator = new OtelPropagator(openTelemetry.getPropagators(), otelTracer);
        return new Bridge(tracer, propagator, sdk, configuration);
    }

    private static OpenTelemetrySdk registerSdk(Configuration configuration) {
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(configuration.otlpEndpoint())
                .setTimeout(Duration.ofSeconds(10))
                .build();
        Resource resource = Resource.getDefault().merge(Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), configuration.serviceName())));
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(configuration.samplingProbability())))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(Duration.ofSeconds(5))
                        .setMaxQueueSize(2048)
                        .build())
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())))
                .build();
        try {
            GlobalOpenTelemetry.set(sdk);
            System.out.printf(Locale.ROOT,
                    "Worker OTel tracing enabled service=%s sampling=%.3f endpoint=%s%n",
                    configuration.serviceName(), configuration.samplingProbability(), configuration.otlpEndpoint());
            return sdk;
        } catch (IllegalStateException alreadyConfigured) {
            // A Java agent or an embedding process may have registered the
            // global provider first. Keep that provider and close our unused
            // exporter rather than failing worker startup.
            sdk.close();
            return null;
        }
    }

    static final class Bridge {
        private static final Propagator.Getter<Map<String, String>> GETTER = Map::get;
        private final Tracer tracer;
        private final Propagator propagator;
        private final OpenTelemetrySdk sdk;
        private final Configuration configuration;

        private Bridge(Tracer tracer, Propagator propagator, OpenTelemetrySdk sdk,
                Configuration configuration) {
            this.tracer = tracer;
            this.propagator = propagator;
            this.sdk = sdk;
            this.configuration = configuration;
        }

        boolean enabled() {
            return configuration.enabled() && !configuration.otlpEndpoint().isBlank();
        }

        GrpcClientTracingInterceptor grpcClientInterceptor() {
            return new GrpcClientTracingInterceptor(tracer, propagator);
        }

        Scope start(String name, EventMetadata metadata) {
            Span span;
            try {
                String traceparent = metadata == null ? "" : metadata.getTraceparent();
                Span.Builder builder = W3cSpanContext.child(tracer, traceparent, name);
                if (builder != null) {
                    span = builder.start();
                } else {
                    Map<String, String> carrier = Map.of("traceparent", traceparent,
                            "tracestate", metadata == null ? "" : metadata.getTracestate());
                    span = propagator.extract(carrier, GETTER).name(name).start();
                }
            } catch (RuntimeException ignored) {
                span = tracer.nextSpan().name(name).start();
            }
            return new Scope(span, tracer.withSpan(span));
        }

        void close() {
            if (sdk != null) {
                sdk.close();
            }
        }
    }

    record Configuration(boolean enabled, double samplingProbability, String otlpEndpoint, String serviceName) {
        static Configuration from(Map<String, String> environment) {
            boolean enabled = booleanValue(environment, ENABLED, false);
            double samplingProbability = decimal(environment, SAMPLING_PROBABILITY, 0.1);
            String endpoint = first(environment, OTLP_ENDPOINT,
                    "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "OTEL_EXPORTER_OTLP_ENDPOINT");
            String serviceName = first(environment, SERVICE_NAME, "OTEL_SERVICE_NAME");
            return new Configuration(enabled, samplingProbability,
                    endpoint == null ? "" : endpoint,
                    serviceName == null ? DEFAULT_SERVICE_NAME : serviceName);
        }

        private static String first(Map<String, String> environment, String... names) {
            for (String name : names) {
                String value = environment.get(name);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return null;
        }

        private static boolean booleanValue(Map<String, String> environment, String name, boolean fallback) {
            String value = first(environment, name);
            return value == null ? fallback : Boolean.parseBoolean(value);
        }

        private static double decimal(Map<String, String> environment, String name, double fallback) {
            String value = first(environment, name);
            if (value == null) return fallback;
            double parsed = Double.parseDouble(value);
            if (parsed < 0.0 || parsed > 1.0 || Double.isNaN(parsed)) {
                throw new IllegalArgumentException(name + " must be between 0 and 1");
            }
            return parsed;
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
