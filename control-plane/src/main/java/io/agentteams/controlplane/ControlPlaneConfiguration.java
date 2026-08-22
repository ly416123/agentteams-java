package io.agentteams.controlplane;

import io.agentteams.application.api.ExecutionEventPort;
import io.agentteams.application.api.ConfigEventPort;
import io.agentteams.application.api.TaskCommandPort;
import io.agentteams.controlplane.application.ControlPlaneExecutionEventAdapter;
import io.agentteams.controlplane.application.ControlPlaneTaskCommandAdapter;
import io.agentteams.controlplane.application.ControlPlaneConfigEventAdapter;
import io.agentteams.controlplane.outbox.EventPublisher;
import io.agentteams.controlplane.outbox.JdbcOutboxStore;
import io.agentteams.controlplane.outbox.NatsEventPublisher;
import io.agentteams.controlplane.outbox.NatsExecutionEventConsumer;
import io.agentteams.controlplane.outbox.OutboxRelay;
import io.agentteams.controlplane.outbox.OutboxRelayProperties;
import io.agentteams.controlplane.outbox.OutboxStore;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.artifact.ArtifactCompletionService;
import io.agentteams.controlplane.artifact.ArtifactService;
import io.agentteams.controlplane.config.ConfigLifecycleRepository;
import io.agentteams.controlplane.config.ConfigSnapshotRepository;
import io.agentteams.controlplane.config.ConfigSnapshotService;
import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.config.ConfigUploadCleanupJob;
import io.agentteams.controlplane.config.ConfigUploadService;
import io.agentteams.controlplane.config.ConfigSnapshotCleanupJob;
import io.agentteams.controlplane.config.ConfigSnapshotCleanupService;
import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import io.agentteams.controlplane.service.SchedulerLeaseService;
import io.agentteams.controlplane.service.TaskAssignmentScheduler;
import io.agentteams.controlplane.service.TaskAssignmentService;
import io.agentteams.controlplane.service.TeamService;
import io.agentteams.controlplane.team.KubernetesTeamResourceSource;
import io.agentteams.controlplane.team.TeamCrdParser;
import io.agentteams.controlplane.team.TeamCrdSynchronizer;
import io.agentteams.controlplane.team.TeamResourceSource;
import io.agentteams.controlplane.service.ExecutionEventService;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.controlplane.storage.MinioObjectStorage;
import io.agentteams.controlplane.storage.MinioObjectStorageConfig;
import io.agentteams.controlplane.storage.ObjectStorage;
import io.agentteams.controlplane.observability.ControlPlaneMetrics;
import io.agentteams.controlplane.observability.TaskMetricsPort;
import io.agentteams.controlplane.observability.AsyncConsumerTracing;
import io.agentteams.controlplane.security.ApiAuthenticationFilter;
import io.agentteams.controlplane.security.IdentityTokenValidator;
import io.agentteams.controlplane.security.OidcIdentityTokenValidator;
import io.agentteams.controlplane.security.OidcSecurityProperties;
import io.agentteams.controlplane.health.NatsConnectionProbe;
import io.nats.client.Connection;
import io.nats.client.Nats;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.io.IOException;
import java.time.Clock;
import io.agentteams.domain.task.TaskTransitionService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OidcSecurityProperties.class)
public class ControlPlaneConfiguration {

    @Bean
    FoundationPersistenceService foundationPersistenceService(DataSource dataSource) {
        return new FoundationPersistenceService(dataSource);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(Clock.class)
    Clock controlPlaneClock() {
        return Clock.systemUTC();
    }

    @Bean
    ControlPlaneMetrics controlPlaneMetrics(ObjectProvider<MeterRegistry> registries) {
        return new ControlPlaneMetrics(registries.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    @Primary
    TaskMetricsPort taskMetricsPort(ObjectProvider<ControlPlaneMetrics> metrics) {
        ControlPlaneMetrics available = metrics.getIfAvailable();
        return available == null ? TaskMetricsPort.noop() : available;
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.security.api.enabled", havingValue = "true")
    FilterRegistrationBean<ApiAuthenticationFilter> apiAuthenticationFilter(IdentityTokenValidator validator) {
        FilterRegistrationBean<ApiAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiAuthenticationFilter(validator));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.security.api.enabled", havingValue = "true")
    IdentityTokenValidator oidcIdentityTokenValidator(OidcSecurityProperties properties) {
        return OidcIdentityTokenValidator.fromProperties(properties);
    }

    @Bean
    ConfigSnapshotRepository configSnapshotRepository(DataSource dataSource) {
        return new ConfigSnapshotRepository(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    ConfigSnapshotService configSnapshotService(ConfigSnapshotRepository repository, Clock clock) {
        return new ConfigSnapshotService(repository, clock);
    }

    @Bean
    ConfigDeploymentService configDeploymentService(FoundationPersistenceService persistence,
            ConfigSnapshotRepository snapshots, Clock clock, ObjectMapper objectMapper) {
        return new ConfigDeploymentService(persistence, snapshots, clock, objectMapper);
    }

    @Bean
    ConfigEventPort configEventPort(ConfigDeploymentService deployments) {
        return new ControlPlaneConfigEventAdapter(deployments);
    }

    @Bean
    ConfigLifecycleRepository configLifecycleRepository(DataSource dataSource) {
        return new ConfigLifecycleRepository(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    SchedulerLeaseRepository schedulerLeaseRepository(DataSource dataSource) {
        return new SchedulerLeaseRepository(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    SchedulerLeaseService schedulerLeaseService(SchedulerLeaseRepository repository) {
        return new SchedulerLeaseService(repository);
    }

    @Bean
    TaskAssignmentService taskAssignmentService(FoundationPersistenceService persistence,
            ObjectProvider<ControlPlaneMetrics> metrics,
            @Value("${agentteams.scheduler.lease-duration:30s}") java.time.Duration leaseDuration) {
        ControlPlaneMetrics available = metrics.getIfAvailable();
        return new TaskAssignmentService(persistence, leaseDuration,
                available == null ? TaskMetricsPort.noop() : available);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "agentteams.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    TaskAssignmentScheduler taskAssignmentScheduler(TaskAssignmentService assignments,
            SchedulerLeaseService schedulerLease, Clock clock,
            @Value("${POD_NAME:}") String podName,
            @Value("${agentteams.scheduler.lease-duration:30s}") java.time.Duration leaseDuration,
            @Value("${agentteams.scheduler.batch-size:16}") int batchSize) {
        return new TaskAssignmentScheduler(assignments, schedulerLease, clock,
                TaskAssignmentScheduler.defaultOwner(podName), leaseDuration, batchSize);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(FoundationPersistenceService.class)
    TeamService teamService(FoundationPersistenceService persistence) {
        return new TeamService(persistence);
    }

    @Bean
    TeamCrdSynchronizer teamCrdSynchronizer(FoundationPersistenceService persistence, Clock clock) {
        return new TeamCrdSynchronizer(persistence, new TeamCrdParser(), clock);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agentteams.team-sync.enabled", havingValue = "true")
    KubernetesClient teamSyncKubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(name = "agentteams.team-sync.enabled", havingValue = "true")
    TeamResourceSource teamResourceSource(KubernetesClient client, TeamCrdSynchronizer synchronizer,
            @Value("${agentteams.team-sync.namespace:}") String namespace) {
        return new KubernetesTeamResourceSource(client, synchronizer, namespace);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "agentteams.storage.enabled",
            havingValue = "true")
    ObjectStorage objectStorage(
            @org.springframework.beans.factory.annotation.Value("${agentteams.storage.endpoint}") String endpoint,
            @org.springframework.beans.factory.annotation.Value("${agentteams.storage.bucket}") String bucket,
            @org.springframework.beans.factory.annotation.Value("${agentteams.storage.access-key}") String accessKey,
            @org.springframework.beans.factory.annotation.Value("${agentteams.storage.secret-key}") String secretKey) {
        return new MinioObjectStorage(new MinioObjectStorageConfig(endpoint, bucket, accessKey, secretKey));
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(ObjectStorage.class)
    ArtifactService artifactService(ObjectStorage storage) {
        return new ArtifactService(storage);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean({ArtifactService.class,
            ConfigSnapshotRepository.class, FoundationPersistenceService.class, ObjectStorage.class})
    ConfigUploadService configUploadService(FoundationPersistenceService persistence,
            ConfigSnapshotRepository snapshots, ObjectStorage storage, ArtifactService verification, Clock clock) {
        return new ConfigUploadService(persistence, snapshots, storage, verification, clock);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(ConfigUploadService.class)
    ConfigUploadCleanupJob configUploadCleanupJob(ConfigUploadService uploads,
            @Value("${agentteams.config.upload-cleanup-batch-size:100}") int batchSize) {
        return new ConfigUploadCleanupJob(uploads, batchSize);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean({FoundationPersistenceService.class,
            ObjectStorage.class})
    ConfigSnapshotCleanupService configSnapshotCleanupService(FoundationPersistenceService persistence,
            ObjectStorage storage) {
        return new ConfigSnapshotCleanupService(persistence, storage);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(ConfigSnapshotCleanupService.class)
    ConfigSnapshotCleanupJob configSnapshotCleanupJob(ConfigSnapshotCleanupService cleanup,
            @Value("${agentteams.config.snapshot-retention-count:5}") int keepCount,
            @Value("${agentteams.config.snapshot-cleanup-batch-size:25}") int batchSize) {
        return new ConfigSnapshotCleanupJob(cleanup, keepCount, batchSize);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean({ArtifactService.class,
            FoundationPersistenceService.class})
    ArtifactCompletionService artifactCompletionService(FoundationPersistenceService persistence,
            ArtifactService artifacts, Clock clock) {
        return new ArtifactCompletionService(persistence, artifacts, clock);
    }

    @Bean
    TaskTransitionService taskTransitionService() {
        return new TaskTransitionService();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(FoundationPersistenceService.class)
    ExecutionEventService executionEventService(FoundationPersistenceService persistence, TaskMetricsPort metrics) {
        return new ExecutionEventService(persistence, metrics);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(TaskService.class)
    TaskCommandPort taskCommandPort(TaskService tasks) {
        return new ControlPlaneTaskCommandAdapter(tasks);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(ExecutionEventService.class)
    ExecutionEventPort executionEventPort(ExecutionEventService executionEvents) {
        return new ControlPlaneExecutionEventAdapter(executionEvents);
    }

    @Bean
    @ConfigurationProperties(prefix = "agentteams.outbox.relay")
    OutboxRelayProperties outboxRelayProperties() {
        return new OutboxRelayProperties();
    }

    @Bean
    OutboxStore outboxStore(FoundationPersistenceService persistence) {
        return new JdbcOutboxStore(persistence);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agentteams.nats.enabled", havingValue = "true")
    Connection natsConnection(@Value("${agentteams.nats.url:nats://localhost:4222}") String url)
            throws IOException, InterruptedException {
        return Nats.connect(url);
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.nats.enabled", havingValue = "true")
    NatsConnectionProbe natsConnectionProbe(Connection connection) {
        return () -> connection.getStatus() == Connection.Status.CONNECTED;
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnProperty(name = "agentteams.nats.enabled", havingValue = "true")
    NatsExecutionEventConsumer natsExecutionEventConsumer(Connection connection,
            ExecutionEventPort executionEvents, ConfigEventPort configEvents, ObjectMapper objectMapper,
            ObjectProvider<Tracer> tracers, ObjectProvider<Propagator> propagators)
            throws IOException {
        return new NatsExecutionEventConsumer(connection.jetStream(), executionEvents, configEvents, objectMapper,
                "control-plane-execution-events", new AsyncConsumerTracing(
                        tracers.getIfAvailable(() -> Tracer.NOOP), propagators.getIfAvailable(() -> Propagator.NOOP)));
    }

    @Bean
    @ConditionalOnProperty(name = {"agentteams.nats.enabled", "agentteams.outbox.relay.enabled"},
            havingValue = "true")
    EventPublisher natsEventPublisher(Connection connection, ObjectMapper objectMapper)
            throws IOException {
        return new NatsEventPublisher(connection.jetStream(), objectMapper);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = {"agentteams.nats.enabled", "agentteams.outbox.relay.enabled"},
            havingValue = "true")
    OutboxRelay outboxRelay(OutboxStore store, EventPublisher publisher, OutboxRelayProperties properties,
            TaskMetricsPort metrics) {
        return new OutboxRelay(store, publisher, properties, Clock.systemUTC(), metrics);
    }
}
