package io.agentteams.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.contracts.v1.AgentChannelGrpc;
import io.agentteams.contracts.v1.AgentMessage;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerInterceptor;
import io.grpc.ServerCallHandler;
import io.grpc.stub.StreamObserver;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import org.junit.jupiter.api.Test;

class GrpcClientTracingInterceptorTest {

    @Test
    void injectsProducerContextIntoInProcessGrpcCall() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span.Builder builder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        io.micrometer.tracing.TraceContext spanContext = mock(io.micrometer.tracing.TraceContext.class);
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(anyString())).thenReturn(span);
        when(span.start()).thenReturn(span);
        when(span.context()).thenReturn(spanContext);
        when(tracer.withSpan(span)).thenReturn(scope);

        AtomicReference<Metadata> received = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
                    io.grpc.ServerCall<ReqT, RespT> call, Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                received.set(headers);
                return next.startCall(call, headers);
            }
        };
        io.grpc.BindableService service = new AgentChannelGrpc.AgentChannelImplBase() {
            @Override
            public StreamObserver<AgentMessage> connect(StreamObserver<io.agentteams.contracts.v1.ServerMessage> response) {
                connected.countDown();
                return new StreamObserver<>() {
                    public void onNext(AgentMessage value) { }
                    public void onError(Throwable error) { }
                    public void onCompleted() { response.onCompleted(); }
                };
            }
        };
        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName).directExecutor()
                .intercept(capture).addService(service).build().start();
        io.grpc.ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        try {
            Propagator propagator = new Propagator() {
                public List<String> fields() { return List.of("traceparent"); }
                public <C> void inject(io.micrometer.tracing.TraceContext context, C carrier,
                        Propagator.Setter<C> setter) {
                    setter.set(carrier, "traceparent",
                            "00-4bf92f3577b34da6a3ce929d0e0e4736-1111111111111111-01");
                }
                public <C> Span.Builder extract(C carrier, Propagator.Getter<C> getter) {
                    return builder;
                }
            };
            io.grpc.Channel traced = ClientInterceptors.intercept(channel,
                    new GrpcClientTracingInterceptor(tracer, propagator));

            StreamObserver<AgentMessage> request = AgentChannelGrpc.newStub(traced).connect(
                    new StreamObserver<>() {
                        public void onNext(io.agentteams.contracts.v1.ServerMessage value) { }
                        public void onError(Throwable error) { }
                        public void onCompleted() { }
                    });
            request.onNext(AgentMessage.getDefaultInstance());
            request.onCompleted();

            assertThat(connected.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get().get(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER)))
                    .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-1111111111111111-01");
            verify(span).end();
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }
}
