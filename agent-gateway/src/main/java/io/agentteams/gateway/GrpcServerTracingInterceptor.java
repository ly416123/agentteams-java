package io.agentteams.gateway;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.concurrent.atomic.AtomicBoolean;

/** Extracts W3C context from gRPC metadata and scopes it over the stream callbacks. */
public final class GrpcServerTracingInterceptor implements ServerInterceptor {
    private static final Propagator.Getter<Metadata> GETTER = (carrier, key) -> {
        try {
            return carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    };

    private final Tracer tracer;
    private final Propagator propagator;

    public GrpcServerTracingInterceptor(Tracer tracer, Propagator propagator) {
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
        this.propagator = propagator == null ? Propagator.NOOP : propagator;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        Span serverSpan;
        try {
            serverSpan = propagator.extract(headers, GETTER).name("agentteams.grpc.agentchannel.server").start();
        } catch (RuntimeException error) {
            serverSpan = tracer.nextSpan().name("agentteams.grpc.agentchannel.server").start().error(error);
        }
        final Span span = serverSpan;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
                private final AtomicBoolean ended = new AtomicBoolean();

                @Override
                public void onMessage(ReqT message) {
                    inSpan(() -> super.onMessage(message));
                }

                @Override
                public void onHalfClose() {
                    inSpan(super::onHalfClose);
                }

                @Override
                public void onReady() {
                    inSpan(super::onReady);
                }

                @Override
                public void onCancel() {
                    try {
                        inSpan(super::onCancel);
                    } finally {
                        endSpan();
                    }
                }

                @Override
                public void onComplete() {
                    try {
                        inSpan(super::onComplete);
                    } finally {
                        endSpan();
                    }
                }

                private void inSpan(Runnable callback) {
                    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                        callback.run();
                    }
                }

                private void endSpan() {
                    if (ended.compareAndSet(false, true)) {
                        span.end();
                    }
                }
            };
        } catch (RuntimeException error) {
            span.error(error);
            span.end();
            throw error;
        }
    }
}
