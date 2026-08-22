package io.agentteams.runtime;

import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.CallOptions;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Creates one OTel client span for the long-lived AgentChannel stream. */
public final class GrpcClientTracingInterceptor implements ClientInterceptor {
    private static final Propagator.Setter<Metadata> SETTER = (carrier, key, value) ->
            carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);

    private final Tracer tracer;
    private final Propagator propagator;

    public GrpcClientTracingInterceptor(Tracer tracer, Propagator propagator) {
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
        this.propagator = propagator == null ? Propagator.NOOP : propagator;
    }

    public static GrpcClientTracingInterceptor noop() {
        return new GrpcClientTracingInterceptor(Tracer.NOOP, Propagator.NOOP);
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions, io.grpc.Channel next) {
        Span span = tracer.nextSpan().name("agentteams.grpc.agentchannel.client").start();
        Metadata headers = new Metadata();
        try {
            propagator.inject(span.context(), headers, SETTER);
            ClientCall<ReqT, RespT> delegate = next.newCall(method, callOptions);
            return new ForwardingClientCall.SimpleForwardingClientCall<>(delegate) {
                private final AtomicBoolean ended = new AtomicBoolean();

                @Override
                public void start(Listener<RespT> responseListener, Metadata responseHeaders) {
                    Listener<RespT> tracedListener = new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(
                            responseListener) {
                        @Override
                        public void onHeaders(Metadata headers) {
                            inSpan(() -> super.onHeaders(headers));
                        }

                        @Override
                        public void onMessage(RespT message) {
                            inSpan(() -> super.onMessage(message));
                        }

                        @Override
                        public void onReady() {
                            inSpan(super::onReady);
                        }

                        @Override
                        public void onClose(Status status, Metadata trailers) {
                            try {
                                inSpan(() -> super.onClose(status, trailers));
                            } finally {
                                if (!status.isOk()) {
                                    span.error(status.asRuntimeException());
                                }
                                endSpan();
                            }
                        }

                        private void inSpan(Runnable callback) {
                            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                                callback.run();
                            }
                        }
                    };
                    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                        responseHeaders.merge(headers);
                        super.start(tracedListener, responseHeaders);
                    }
                }

                @Override
                public void cancel(String message, Throwable cause) {
                    if (cause != null) {
                        span.error(cause);
                    }
                    endSpan();
                    super.cancel(message, cause);
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

    /** Applies this interceptor without exposing gRPC-specific setup at call sites. */
    public static io.grpc.Channel intercept(io.grpc.Channel channel, Tracer tracer, Propagator propagator) {
        return ClientInterceptors.intercept(Objects.requireNonNull(channel, "channel"),
                new GrpcClientTracingInterceptor(tracer, propagator));
    }
}
