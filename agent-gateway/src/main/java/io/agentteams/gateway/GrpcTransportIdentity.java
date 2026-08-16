package io.agentteams.gateway;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.Locale;

/** Extracts a transport credential into gRPC request context without placing it in logs. */
public final class GrpcTransportIdentity {
    private static final Context.Key<String> IDENTITY = Context.key("agentteams.transport-identity");
    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization",
            Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AGENT_TOKEN = Metadata.Key.of("x-agent-token",
            Metadata.ASCII_STRING_MARSHALLER);

    private GrpcTransportIdentity() { }

    public static String current() {
        String value = IDENTITY.get();
        return value == null ? "" : value;
    }

    static final class Interceptor implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            String token = headers.get(AGENT_TOKEN);
            if (token == null || token.isBlank()) {
                String authorization = headers.get(AUTHORIZATION);
                if (authorization != null && authorization.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                    token = authorization.substring("bearer ".length()).trim();
                }
            }
            Context context = Context.current().withValue(IDENTITY, token == null ? "" : token);
            return Contexts.interceptCall(context, call, headers, next);
        }
    }
}
