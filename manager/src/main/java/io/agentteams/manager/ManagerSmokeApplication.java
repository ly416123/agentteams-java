package io.agentteams.manager;

import io.agentteams.application.api.QuotaPort;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.net.URI;
import java.nio.file.Path;

/** Minimal local-only entry point for verifying Manager -> ChatGPT plan OAuth connectivity. */
public final class ManagerSmokeApplication {
    private static final String PROVIDER = "chatgpt-plan";
    private static final int SMOKE_MAX_TOKENS = 256;

    private ManagerSmokeApplication() {}

    public static void main(String[] args) {
        String model = text(System.getenv("AGENTTEAMS_MODEL"), ChatGptPlanResponsesProvider.DEFAULT_MODEL);
        String tokenPath = text(System.getenv("AGENTTEAMS_CHATGPT_ACCESS_TOKEN_FILE"), "");
        if (tokenPath.isBlank()) {
            throw new IllegalArgumentException("AGENTTEAMS_CHATGPT_ACCESS_TOKEN_FILE must be set");
        }
        String proxy = text(System.getenv("AGENTTEAMS_CHATGPT_PROXY_URL"), "");
        ManagerSmokeConfiguration smokeConfiguration = ManagerSmokeConfiguration.fromEnvironment();
        ChatGptPlanResponsesProvider provider = new ChatGptPlanResponsesProvider(model, Path.of(tokenPath),
                ChatGptPlanResponsesProvider.DEFAULT_ENDPOINT,
                ChatGptPlanResponsesProvider.httpClient(proxy), java.time.Duration.ofSeconds(90));
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
                    PROVIDER, model, SMOKE_MAX_TOKENS,
                    smokeConfiguration.tenantId(), smokeConfiguration.projectId(),
                    ModelCallDimensions.empty()));
            if (lease == null) {
                throw new IllegalStateException("model call admission returned null lease");
            }
            try {
                ModelProvider.ModelResponse response = provider.complete(new ModelProvider.ModelRequest(
                        "Reply with a short connectivity confirmation.", SMOKE_MAX_TOKENS));
                if (response.content().isBlank()) {
                    throw new IllegalStateException("ChatGPT plan smoke response was empty");
                }
            } finally {
                lease.close();
            }
            System.out.println("CHATGPT_PLAN_MANAGER_OK model=" + model);
        } finally {
            if (quotaPort instanceof GrpcQuotaPort grpcQuotaPort) {
                grpcQuotaPort.close();
            } else if (channel != null) {
                channel.shutdown();
            }
        }
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
