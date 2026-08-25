package io.agentteams.operator;

import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.config.LeaderElectionConfiguration;
import io.javaoperatorsdk.operator.api.config.LeaderElectionConfigurationBuilder;
import java.time.Duration;
import java.util.UUID;

public final class AgentTeamsOperatorApplication {
    private AgentTeamsOperatorApplication() { }

    public static void main(String[] args) {
        Operator operator = operatorFromEnvironment();
        registerControllers(operator);
        operator.installShutdownHook();
        operator.start();
    }

    private static void registerControllers(Operator operator) {
        String namespace = System.getenv("AGENTTEAMS_OPERATOR_NAMESPACE");
        if (namespace == null || namespace.isBlank()) {
            operator.register(new WorkerReconciler(operator.getKubernetesClient()));
            operator.register(new TeamReconciler(operator.getKubernetesClient()));
            operator.register(new TaskSandboxReconciler(operator.getKubernetesClient()));
            return;
        }
        operator.register(new WorkerReconciler(operator.getKubernetesClient()),
                configuration -> configuration.settingNamespace(namespace));
        operator.register(new TeamReconciler(operator.getKubernetesClient()),
                configuration -> configuration.settingNamespace(namespace));
        operator.register(new TaskSandboxReconciler(operator.getKubernetesClient()),
                configuration -> configuration.settingNamespace(namespace));
    }

    static Operator operatorFromEnvironment() {
        if (!Boolean.parseBoolean(System.getenv().getOrDefault("AGENTTEAMS_OPERATOR_LEADER_ELECTION", "false"))) {
            return new Operator();
        }
        String leaseName = textOrDefault(System.getenv("AGENTTEAMS_OPERATOR_LEASE_NAME"),
                "agentteams-operator");
        String namespace = textOrDefault(System.getenv("AGENTTEAMS_OPERATOR_LEASE_NAMESPACE"), "default");
        String identity = textOrDefault(System.getenv("POD_NAME"),
                "agentteams-operator-" + UUID.randomUUID());
        LeaderElectionConfiguration election = LeaderElectionConfigurationBuilder
                .aLeaderElectionConfiguration(leaseName)
                .withLeaseNamespace(namespace)
                .withIdentity(identity)
                .withLeaseDuration(Duration.ofSeconds(30))
                .withRenewDeadline(Duration.ofSeconds(20))
                .withRetryPeriod(Duration.ofSeconds(5))
                .build();
        return new Operator(overrider -> overrider.withLeaderElectionConfiguration(election));
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
