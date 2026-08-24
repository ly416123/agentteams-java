package io.agentteams.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.net.http.HttpClient;

/** Minimal local-only entry point for verifying Manager -> DeepSeek connectivity. */
public final class ManagerSmokeApplication {
    private static final String PROVIDER = "deepseek";
    private static final int SMOKE_MAX_TOKENS = 256;

    private ManagerSmokeApplication() {}

    public static void main(String[] args) {
        DeepSeekConfiguration configuration = DeepSeekConfiguration.fromEnvironment(System.getenv());
        ManagerSmokeConfiguration smokeConfiguration = ManagerSmokeConfiguration.fromEnvironment();
        DeepSeekProvider provider = configuration.createProvider(HttpClient.newHttpClient(), new ObjectMapper());
        ManagedChannel channel = null;
        QuotaPort quotaPort = QuotaPort.noop();
        try {
            if (smokeConfiguration.remoteQuotaEnabled()) {
                channel = ManagedChannelBuilder
                        .forAddress(smokeConfiguration.gatewayHost(), smokeConfiguration.gatewayPort())
                        .usePlaintext()
                        .build();
                quotaPort = ManagerQuotaPortFactory.fromEnvironment(channel, smokeConfiguration.managerId());
            }
            ModelCallAdmission admission = new ProjectScopedModelCallAdmission(quotaPort);
            ModelCallLease lease = admission.acquire(new ModelCallAdmissionRequest(
                    PROVIDER, configuration.model(), SMOKE_MAX_TOKENS,
                    smokeConfiguration.tenantId(), smokeConfiguration.projectId(),
                    ModelCallDimensions.empty()));
            if (lease == null) {
                throw new IllegalStateException("model call admission returned null lease");
            }
            try {
                ModelProvider.ModelResponse response = provider.complete(new ModelProvider.ModelRequest(
                        "Reply with a short connectivity confirmation.", SMOKE_MAX_TOKENS));
                if (response.content().isBlank()) {
                    throw new IllegalStateException("DeepSeek smoke response was empty");
                }
            } finally {
                lease.close();
            }
            System.out.println("DEEPSEEK_MANAGER_OK model=" + configuration.model());
        } finally {
            if (quotaPort instanceof GrpcQuotaPort grpcQuotaPort) {
                grpcQuotaPort.close();
            } else if (channel != null) {
                channel.shutdown();
            }
        }
    }
}
