package io.agentteams.worker;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.Objects;
import java.util.function.Supplier;

/** Attaches the enrolled Worker session token to Gateway gRPC calls. */
final class GrpcSessionAuthInterceptor implements ClientInterceptor {
    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization",
            Metadata.ASCII_STRING_MARSHALLER);

    private final Supplier<String> token;

    GrpcSessionAuthInterceptor(Supplier<String> token) {
        this.token = Objects.requireNonNull(token, "token");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions, Channel next) {
        ClientCall<ReqT, RespT> delegate = next.newCall(method, callOptions);
        return new ForwardingClientCall.SimpleForwardingClientCall<>(delegate) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String currentToken = token.get();
                if (currentToken != null && !currentToken.isBlank()) {
                    headers.put(AUTHORIZATION, "Bearer " + currentToken.trim());
                }
                super.start(responseListener, headers);
            }
        };
    }
}
