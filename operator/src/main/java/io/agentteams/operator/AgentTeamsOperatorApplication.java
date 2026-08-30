package io.agentteams.operator;

import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.api.config.LeaderElectionConfiguration;
import io.javaoperatorsdk.operator.api.config.LeaderElectionConfigurationBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

public final class AgentTeamsOperatorApplication {
    private AgentTeamsOperatorApplication() { }

    public static void main(String[] args) {
        Operator operator = operatorFromEnvironment();
        registerControllers(operator, observationReporterFromEnvironment(), recoveryFromEnvironment(),
                directiveReaderFromEnvironment());
        operator.installShutdownHook();
        operator.start();
    }

    private static void registerControllers(Operator operator, WorkerOperationObservationReporter observations,
            WorkerOperationRecovery recovery, WorkerOperationDirectiveReader directives) {
        String namespace = System.getenv("AGENTTEAMS_OPERATOR_NAMESPACE");
        if (namespace == null || namespace.isBlank()) {
            operator.register(new WorkerReconciler(operator.getKubernetesClient(), observations, recovery,
                    new ObjectMapper(), directives));
            operator.register(new TeamReconciler(operator.getKubernetesClient()));
            operator.register(new TaskSandboxReconciler(operator.getKubernetesClient()));
            return;
        }
        operator.register(new WorkerReconciler(operator.getKubernetesClient(), observations, recovery,
                        new ObjectMapper(), directives),
                configuration -> configuration.settingNamespace(namespace));
        operator.register(new TeamReconciler(operator.getKubernetesClient()),
                configuration -> configuration.settingNamespace(namespace));
        operator.register(new TaskSandboxReconciler(operator.getKubernetesClient()),
                configuration -> configuration.settingNamespace(namespace));
    }

    static WorkerOperationObservationReporter observationReporterFromEnvironment() {
        String endpoint = System.getenv("AGENTTEAMS_CONTROL_PLANE_URL");
        String token = System.getenv("AGENTTEAMS_OPERATOR_INTERNAL_TOKEN");
        if (endpoint == null || endpoint.isBlank() || token == null || token.isBlank()) {
            return WorkerOperationObservationReporter.noop();
        }
        return new HttpWorkerOperationObservationReporter(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build(), new ObjectMapper(),
                URI.create(endpoint.trim()), token);
    }

    static WorkerOperationRecovery recoveryFromEnvironment() {
        String endpoint = System.getenv("AGENTTEAMS_CONTROL_PLANE_URL");
        String token = System.getenv("AGENTTEAMS_OPERATOR_INTERNAL_TOKEN");
        if (endpoint == null || endpoint.isBlank() || token == null || token.isBlank()) {
            return WorkerOperationRecovery.noop();
        }
        return new HttpWorkerOperationRecovery(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build(), new ObjectMapper(),
                URI.create(endpoint.trim()), token, Duration.ofSeconds(3));
    }

    static WorkerOperationDirectiveReader directiveReaderFromEnvironment() {
        String endpoint = System.getenv("AGENTTEAMS_CONTROL_PLANE_URL");
        String token = System.getenv("AGENTTEAMS_OPERATOR_INTERNAL_TOKEN");
        if (endpoint == null || endpoint.isBlank() || token == null || token.isBlank()) {
            return WorkerOperationDirectiveReader.noop();
        }
        return new HttpWorkerOperationDirectiveReader(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build(), new ObjectMapper(),
                URI.create(endpoint.trim()), token, Duration.ofSeconds(3));
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
