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
import io.agentteams.controlplane.artifact.ArtifactRetentionCleanupJob;
import io.agentteams.controlplane.artifact.ArtifactRetentionPolicy;
import io.agentteams.controlplane.artifact.ArtifactRetentionService;
import io.agentteams.controlplane.config.ConfigLifecycleRepository;
import io.agentteams.controlplane.config.ConfigSnapshotRepository;
import io.agentteams.controlplane.config.ConfigSnapshotService;
import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.config.ConfigUploadCleanupJob;
import io.agentteams.controlplane.config.ConfigUploadService;
import io.agentteams.controlplane.agentspec.AgentSpecDeploymentService;
import io.agentteams.controlplane.agent.AgentPresenceConsistencyJob;
import io.agentteams.controlplane.agent.AgentPresenceConsistencyRepository;
import io.agentteams.controlplane.agent.AgentPresenceConsistencyService;
import io.agentteams.controlplane.agent.JdbcAgentPresenceConsistencyRepository;
import io.agentteams.controlplane.config.ConfigSnapshotCleanupJob;
import io.agentteams.controlplane.config.ConfigSnapshotCleanupService;
import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import io.agentteams.controlplane.service.SchedulerLeaseService;
import io.agentteams.controlplane.service.TaskAssignmentScheduler;
import io.agentteams.controlplane.service.TaskAssignmentService;
import io.agentteams.controlplane.worker.WorkerOperationRecoveryScheduler;
import io.agentteams.controlplane.worker.WorkerCrdProvisioner;
import io.agentteams.controlplane.worker.KubernetesWorkerCrdProvisioner;
import io.agentteams.controlplane.service.TeamService;
import io.agentteams.controlplane.team.KubernetesTeamResourceSource;
import io.agentteams.controlplane.team.TeamCrdParser;
import io.agentteams.controlplane.team.TeamCrdSynchronizer;
import io.agentteams.controlplane.team.TeamResourceSource;
import io.agentteams.controlplane.team.TeamRevisionRepository;
import io.agentteams.controlplane.team.TeamRevisionService;
import io.agentteams.controlplane.team.TeamRevisionPublishValidator;
import io.agentteams.controlplane.team.CatalogTeamRevisionPublishValidator;
import io.agentteams.controlplane.agentspec.AgentSpecReferenceValidator;
import io.agentteams.controlplane.team.TeamDeploymentRepository;
import io.agentteams.controlplane.team.TeamDeploymentService;
import io.agentteams.controlplane.service.ExecutionEventService;
import io.agentteams.controlplane.sandbox.SandboxLifecycleService;
import io.agentteams.controlplane.sandbox.FakeSandboxRuntime;
import io.agentteams.controlplane.sandbox.KubernetesSandboxRuntime;
import io.agentteams.controlplane.sandbox.SandboxLifecycleScheduler;
import io.agentteams.controlplane.sandbox.SandboxRuntimeProperties;
import io.agentteams.controlplane.sandbox.SandboxPolicyService;
import io.agentteams.application.api.SandboxRuntimePort;
import io.agentteams.controlplane.audit.JdbcModelCallAuditRecorder;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.controlplane.storage.MinioObjectStorage;
import io.agentteams.controlplane.storage.MinioObjectStorageConfig;
import io.agentteams.controlplane.storage.ObjectStorage;
import io.agentteams.controlplane.observability.ControlPlaneMetrics;
import io.agentteams.controlplane.observability.TaskMetricsPort;
import io.agentteams.controlplane.observability.AsyncConsumerTracing;
import io.agentteams.controlplane.observability.AsyncProducerTracing;
import io.agentteams.controlplane.security.ApiAuthenticationFilter;
import io.agentteams.controlplane.security.IdentityTokenValidator;
import io.agentteams.controlplane.security.OidcIdentityTokenValidator;
import io.agentteams.controlplane.security.OidcSecurityProperties;
import io.agentteams.controlplane.security.SecretResolver;
import io.agentteams.controlplane.security.ValidationOnlySecretResolver;
import io.agentteams.controlplane.security.CredentialSecretProvider;
import io.agentteams.controlplane.security.UnavailableCredentialSecretProvider;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.JdbcExecutionContextDirectory;
import io.agentteams.controlplane.service.ModelProviderConnectionProbe;
import io.agentteams.controlplane.service.ValidationOnlyModelProviderConnectionProbe;
import io.agentteams.controlplane.dashboard.DashboardAlertDeliveryService;
import io.agentteams.controlplane.dashboard.DashboardAlertEventRepository;
import io.agentteams.controlplane.dashboard.DashboardAlertScheduler;
import io.agentteams.controlplane.dashboard.DashboardAlertService;
import io.agentteams.controlplane.dashboard.DashboardAlertNotificationPort;
import io.agentteams.controlplane.usage.UsageQueryService;
import io.agentteams.controlplane.usage.UsageBudgetRepository;
import io.agentteams.controlplane.usage.UsageBudgetService;
import io.agentteams.controlplane.usage.UsageBudgetNotificationPort;
import io.agentteams.controlplane.usage.LoggingUsageBudgetNotificationPort;
import io.agentteams.controlplane.usage.UsageBudgetDeliveryService;
import io.agentteams.controlplane.usage.UsageBudgetScheduler;
import io.agentteams.controlplane.service.HttpModelPriceSyncClient;
import io.agentteams.controlplane.service.ModelPriceSyncPort;
import io.agentteams.controlplane.service.ModelPriceSyncProperties;
import io.agentteams.controlplane.service.ModelPriceSyncScheduler;
import io.agentteams.controlplane.service.ModelPriceSyncService;
import io.agentteams.controlplane.task.TaskStateConsistencyChecker;
import io.agentteams.controlplane.task.TaskStateConsistencyJob;
import io.agentteams.controlplane.task.TaskStateConsistencyRepository;
import io.agentteams.controlplane.task.TaskStateConsistencyService;
import io.agentteams.controlplane.webhook.WebhookDeliveryScheduler;
import io.agentteams.controlplane.webhook.WebhookDeliveryService;
import io.agentteams.controlplane.channel.WebhookChannelAdapter;
import io.agentteams.controlplane.webhook.WebhookSecretResolver;
import io.agentteams.controlplane.webhook.WebhookHmacTransport;
import io.agentteams.controlplane.webhook.WebhookRepository;
import io.agentteams.controlplane.webhook.WebhookTransport;
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
import org.springframework.beans.factory.annotation.Qualifier;
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
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({OidcSecurityProperties.class, SandboxRuntimeProperties.class,
        ModelPriceSyncProperties.class})
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
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(SecretResolver.class)
    SecretResolver secretResolver() {
        return new ValidationOnlySecretResolver();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(CredentialSecretProvider.class)
    CredentialSecretProvider credentialSecretProvider() {
        return new UnavailableCredentialSecretProvider();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(ModelProviderConnectionProbe.class)
    ModelProviderConnectionProbe modelProviderConnectionProbe(SecretResolver secretResolver) {
        return new ValidationOnlyModelProviderConnectionProbe(secretResolver);
    }

    @Bean
    @Primary
    TaskMetricsPort taskMetricsPort(ObjectProvider<ControlPlaneMetrics> metrics) {
        ControlPlaneMetrics available = metrics.getIfAvailable();
        return available == null ? TaskMetricsPort.noop() : available;
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.security.api.enabled", havingValue = "true")
    FilterRegistrationBean<ApiAuthenticationFilter> apiAuthenticationFilter(IdentityTokenValidator validator,
            io.agentteams.controlplane.project.ProjectRepository projects) {
        FilterRegistrationBean<ApiAuthenticationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiAuthenticationFilter(validator,
                new io.agentteams.controlplane.security.ProjectScopeResolver(projects)));
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
    ExecutionContextResolver executionContextResolver(JdbcExecutionContextDirectory directory) {
        return new ExecutionContextResolver(directory);
    }

    @Bean
    ConfigSnapshotService configSnapshotService(ConfigSnapshotRepository repository, Clock clock) {
        return new ConfigSnapshotService(repository, clock);
    }

    @Bean
    ConfigDeploymentService configDeploymentService(FoundationPersistenceService persistence,
            ConfigSnapshotRepository snapshots, Clock clock, ObjectMapper objectMapper,
            ControlPlaneMetrics metrics) {
        return new ConfigDeploymentService(persistence, snapshots, clock, objectMapper, metrics);
    }

    @Bean
    ConfigEventPort configEventPort(ConfigDeploymentService deployments, TeamDeploymentService teamDeployments) {
        return new ControlPlaneConfigEventAdapter(deployments, teamDeployments);
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
    io.agentteams.controlplane.schedule.ScheduledTaskRepository scheduledTaskRepository(DataSource dataSource) {
        return new io.agentteams.controlplane.schedule.JdbcScheduledTaskRepository(
                new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    io.agentteams.controlplane.schedule.ScheduledTaskRunRepository scheduledTaskRunRepository(DataSource dataSource) {
        return new io.agentteams.controlplane.schedule.JdbcScheduledTaskRunRepository(
                new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "agentteams.scheduled-tasks.enabled", havingValue = "true", matchIfMissing = true)
    io.agentteams.controlplane.schedule.ScheduledTaskScheduler scheduledTaskScheduler(
            io.agentteams.controlplane.schedule.ScheduledTaskRepository schedules, TaskService tasks,
            io.agentteams.controlplane.schedule.ScheduledTaskRunRepository runs,
            SchedulerLeaseService schedulerLease, Clock clock,
            @Value("${POD_NAME:}") String podName,
            @Value("${agentteams.scheduler.lease-duration:30s}") java.time.Duration leaseDuration,
            @Value("${agentteams.scheduled-tasks.batch-size:16}") int batchSize) {
        return new io.agentteams.controlplane.schedule.ScheduledTaskScheduler(schedules, tasks, runs, schedulerLease,
                clock, TaskAssignmentScheduler.defaultOwner(podName), leaseDuration, batchSize);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(WebhookSecretResolver.class)
    WebhookSecretResolver webhookSecretResolver() {
        return secretRef -> {
            throw new IllegalStateException("Webhook secret resolver is not configured: " + secretRef);
        };
    }

    @Bean
    WebhookTransport webhookTransport(WebhookSecretResolver secrets,
            @Value("${agentteams.webhook.transport.timeout:10s}") java.time.Duration timeout) {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(timeout).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        return new WebhookHmacTransport(client, secrets, timeout);
    }

    @Bean
    WebhookChannelAdapter webhookChannelAdapter(WebhookRepository repository, Clock clock) {
        return new WebhookChannelAdapter(repository, clock);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "agentteams.webhook.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    WebhookDeliveryScheduler webhookDeliveryScheduler(WebhookDeliveryService delivery,
            SchedulerLeaseService schedulerLease, Clock clock,
            @Value("${POD_NAME:}") String podName,
            @Value("${agentteams.webhook.scheduler.lease-duration:30s}") java.time.Duration leaseDuration,
            @Value("${agentteams.webhook.scheduler.batch-size:16}") int batchSize) {
        return new WebhookDeliveryScheduler(delivery, schedulerLease, clock,
                TaskAssignmentScheduler.defaultOwner(podName), leaseDuration, batchSize);
    }

    @Bean
    TaskAssignmentService taskAssignmentService(FoundationPersistenceService persistence,
            ObjectProvider<ControlPlaneMetrics> metrics,
            ObjectProvider<ExecutionContextResolver> contextResolver,
            ObjectProvider<io.agentteams.controlplane.memory.ContextAssemblyService> contextAssembly,
            @Value("${agentteams.scheduler.lease-duration:30s}") java.time.Duration leaseDuration) {
        ControlPlaneMetrics available = metrics.getIfAvailable();
        return new TaskAssignmentService(persistence, leaseDuration,
                available == null ? TaskMetricsPort.noop() : available,
                contextResolver.getIfAvailable(), contextAssembly.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.sandbox.provider", havingValue = "fake")
    SandboxRuntimePort fakeSandboxRuntime() {
        return new FakeSandboxRuntime();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agentteams.sandbox.provider", havingValue = "kubernetes")
    KubernetesClient sandboxKubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.sandbox.provider", havingValue = "kubernetes")
    SandboxRuntimePort kubernetesSandboxRuntime(KubernetesClient sandboxKubernetesClient,
            SandboxRuntimeProperties properties, Clock clock) {
        return new KubernetesSandboxRuntime(sandboxKubernetesClient, properties.getNamespace(), clock, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.sandbox.enabled", havingValue = "true")
    SandboxLifecycleService sandboxLifecycleService(FoundationPersistenceService persistence,
            SandboxRuntimePort runtime,
            SandboxRuntimeProperties properties,
            ObjectProvider<io.agentteams.controlplane.memory.TaskMemoryContextAssembler> memoryContexts) {
        return new SandboxLifecycleService(persistence, runtime, properties,
                "sandbox-lifecycle", new SandboxPolicyService(), memoryContexts.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.sandbox.enabled", havingValue = "true")
    SandboxLifecycleScheduler sandboxLifecycleScheduler(SandboxLifecycleService lifecycle,
            SchedulerLeaseService schedulerLease, Clock clock, SandboxRuntimeProperties properties,
            @Value("${POD_NAME:}") String podName,
            @Value("${agentteams.scheduler.lease-duration:30s}") java.time.Duration leaseDuration) {
        String owner = TaskAssignmentScheduler.defaultOwner(podName);
        return new SandboxLifecycleScheduler(lifecycle, schedulerLease, clock, owner, leaseDuration, properties);
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
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "agentteams.worker-operations.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    WorkerOperationRecoveryScheduler workerOperationRecoveryScheduler(
            FoundationPersistenceService persistence, SchedulerLeaseService schedulerLease, Clock clock,
            @Value("${POD_NAME:}") String podName,
            @Value("${agentteams.scheduler.lease-duration:30s}") java.time.Duration leaseDuration) {
        return new WorkerOperationRecoveryScheduler(persistence, schedulerLease, clock,
                TaskAssignmentScheduler.defaultOwner(podName), leaseDuration);
    }

    @Bean
    DashboardAlertDeliveryService dashboardAlertDeliveryService(UsageQueryService usage,
            DashboardAlertService alerts, DashboardAlertEventRepository events,
            DashboardAlertNotificationPort notifications, Clock clock,
            @Value("${agentteams.dashboard.alerts.scheduler.retry-delay:1m}") java.time.Duration retryDelay) {
        return new DashboardAlertDeliveryService(usage::summarizeForScope, alerts, events, notifications,
                clock, retryDelay);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "agentteams.dashboard.alerts.scheduler.enabled", havingValue = "true")
    DashboardAlertScheduler dashboardAlertScheduler(DashboardAlertDeliveryService delivery,
            DashboardAlertEventRepository events, SchedulerLeaseService schedulerLease, Clock clock,
            @Value("${POD_NAME:}") String podName,
            @Value("${agentteams.dashboard.alerts.scheduler.lease-duration:30s}") java.time.Duration leaseDuration,
            @Value("${agentteams.dashboard.alerts.scheduler.window:24h}") java.time.Duration window,
            @Value("${agentteams.dashboard.alerts.scheduler.max-projects-per-run:100}") int maxProjectsPerRun) {
        return new DashboardAlertScheduler(delivery, events, schedulerLease, clock,
                podName == null || podName.isBlank() ? "dashboard-alert" : podName,
                leaseDuration, window, maxProjectsPerRun);
    }

    @Bean
    UsageBudgetNotificationPort usageBudgetNotificationPort() {
        return new LoggingUsageBudgetNotificationPort();
    }

    @Bean
    UsageBudgetDeliveryService usageBudgetDeliveryService(UsageBudgetRepository events, UsageBudgetService budgets,
            UsageBudgetNotificationPort notifications, Clock clock,
            @Value("${agentteams.usage.budget.scheduler.retry-delay:1m}") java.time.Duration retryDelay) {
        return new UsageBudgetDeliveryService(events, budgets, notifications, clock, retryDelay);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "agentteams.usage.budget.scheduler.enabled", havingValue = "true")
    UsageBudgetScheduler usageBudgetScheduler(UsageBudgetDeliveryService delivery, SchedulerLeaseService schedulerLease,
            Clock clock, @Value("${POD_NAME:}") String podName,
            @Value("${agentteams.usage.budget.scheduler.lease-duration:30s}") java.time.Duration leaseDuration,
            @Value("${agentteams.usage.budget.scheduler.max-policies-per-run:100}") int maxPoliciesPerRun) {
        return new UsageBudgetScheduler(delivery, schedulerLease, clock,
                podName == null || podName.isBlank() ? "usage-budget" : podName,
                leaseDuration, maxPoliciesPerRun);
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.usage.price-sync.enabled", havingValue = "true")
    ModelPriceSyncPort modelPriceSyncPort(ModelPriceSyncProperties properties, ObjectMapper objectMapper) {
        properties.validate();
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        return new HttpModelPriceSyncClient(client, objectMapper, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.usage.price-sync.enabled", havingValue = "true")
    ModelPriceSyncService modelPriceSyncService(ModelPriceSyncPort source,
            FoundationPersistenceService persistence, ModelPriceSyncProperties properties) {
        return new ModelPriceSyncService(source, persistence, properties.getTargets(), properties.getMaxQuotes());
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.usage.price-sync.enabled", havingValue = "true")
    ModelPriceSyncScheduler modelPriceSyncScheduler(ModelPriceSyncService sync,
            SchedulerLeaseService schedulerLease, Clock clock, ModelPriceSyncProperties properties,
            @Value("${POD_NAME:}") String podName) {
        return new ModelPriceSyncScheduler(sync, schedulerLease, clock,
                podName == null || podName.isBlank() ? "model-price-sync" : podName,
                properties.getLeaseDuration());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(FoundationPersistenceService.class)
    TeamService teamService(FoundationPersistenceService persistence,
            io.agentteams.controlplane.security.ResourceScopeRepository resourceScopes,
            io.agentteams.controlplane.service.IdempotencyService idempotency) {
        return new TeamService(persistence, new io.agentteams.controlplane.team.TeamSchedulingPolicy(),
                resourceScopes, idempotency);
    }

    @Bean
    TeamRevisionRepository teamRevisionRepository(DataSource dataSource) {
        return new TeamRevisionRepository(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    TeamRevisionPublishValidator teamRevisionPublishValidator(TeamRevisionRepository repository,
            io.agentteams.controlplane.security.ResourceScopeRepository resourceScopes,
            AgentSpecReferenceValidator references) {
        return new CatalogTeamRevisionPublishValidator(repository, resourceScopes, references);
    }

    @Bean
    TeamRevisionService teamRevisionService(TeamRevisionRepository repository,
            TeamRevisionPublishValidator publishValidator,
            io.agentteams.controlplane.security.ResourceScopeRepository resourceScopes) {
        return new TeamRevisionService(repository, publishValidator, resourceScopes);
    }

    @Bean
    TeamDeploymentRepository teamDeploymentRepository(DataSource dataSource) {
        return new TeamDeploymentRepository(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    TeamDeploymentService teamDeploymentService(TeamDeploymentRepository repository,
            ConfigSnapshotService snapshots, ConfigDeploymentService deployments, Clock clock,
            TeamRevisionRepository revisions,
            io.agentteams.controlplane.security.ResourceScopeRepository resourceScopes) {
        return new TeamDeploymentService(repository, snapshots, deployments, new io.agentteams.controlplane.config.EffectiveConfigComposer(),
                clock, revisions, resourceScopes);
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
    TeamResourceSource teamResourceSource(@Qualifier("teamSyncKubernetesClient") KubernetesClient client,
            TeamCrdSynchronizer synchronizer,
            @Value("${agentteams.team-sync.namespace:}") String namespace) {
        return new KubernetesTeamResourceSource(client, synchronizer, namespace);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "agentteams.worker-provisioner.enabled", havingValue = "true")
    KubernetesClient workerProvisionerKubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.worker-provisioner.enabled", havingValue = "true")
    WorkerCrdProvisioner kubernetesWorkerCrdProvisioner(
            @Qualifier("workerProvisionerKubernetesClient") KubernetesClient client,
            @Value("${agentteams.worker-provisioner.namespace:}") String namespace) {
        return new KubernetesWorkerCrdProvisioner(client, namespace);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "agentteams.storage.enabled",
            havingValue = "true")
    ObjectStorage objectStorage(
            @org.springframework.beans.factory.annotation.Value("${agentteams.storage.endpoint}") String endpoint,
            @org.springframework.beans.factory.annotation.Value("${agentteams.storage.bucket}") String bucket,
            @org.springframework.beans.factory.annotation.Value("${agentteams.storage.access-key}") String accessKey,
            @org.springframework.beans.factory.annotation.Value("${agentteams.storage.secret-key}") String secretKey,
            @org.springframework.beans.factory.annotation.Value("${agentteams.storage.presign-endpoint:}") String presignEndpoint,
            @org.springframework.beans.factory.annotation.Value("${agentteams.storage.region:}") String region) {
        return new MinioObjectStorage(new MinioObjectStorageConfig(
                endpoint, bucket, accessKey, secretKey, presignEndpoint, region));
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
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean({
            io.agentteams.controlplane.artifact.ArtifactRetentionRepository.class, ObjectStorage.class})
    ArtifactRetentionService artifactRetentionService(
            io.agentteams.controlplane.artifact.ArtifactRetentionRepository repository, ObjectStorage storage,
            Clock clock) {
        return new ArtifactRetentionService(repository, storage, clock);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean({ArtifactRetentionService.class,
            SchedulerLeaseService.class})
    ArtifactRetentionCleanupJob artifactRetentionCleanupJob(ArtifactRetentionService retention,
            SchedulerLeaseService lease, Clock clock,
            @Value("${POD_NAME:}") String podName,
            @Value("${agentteams.scheduler.lease-duration:30s}") java.time.Duration leaseDuration,
            @Value("${agentteams.artifact-retention.successful-task-retention:30d}") java.time.Duration successfulRetention,
            @Value("${agentteams.artifact-retention.failed-task-retention:90d}") java.time.Duration failedRetention,
            @Value("${agentteams.artifact-retention.temporary-upload-retention:2h}") java.time.Duration temporaryRetention,
            @Value("${agentteams.artifact-retention.legal-hold:false}") boolean legalHold,
            @Value("${agentteams.artifact-retention.batch-size:100}") int batchSize) {
        ArtifactRetentionPolicy fallback = new ArtifactRetentionPolicy(successfulRetention, failedRetention,
                temporaryRetention, legalHold);
        return new ArtifactRetentionCleanupJob(retention, lease, clock,
                TaskAssignmentScheduler.defaultOwner(podName), leaseDuration, fallback, batchSize);
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.task-state-consistency.enabled", havingValue = "true", matchIfMissing = true)
    TaskStateConsistencyService taskStateConsistencyService(TaskStateConsistencyRepository repository,
            TaskMetricsPort metrics) {
        return new TaskStateConsistencyService(repository, new TaskStateConsistencyChecker(), metrics);
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.task-state-consistency.enabled", havingValue = "true", matchIfMissing = true)
    TaskStateConsistencyJob taskStateConsistencyJob(TaskStateConsistencyService service,
            SchedulerLeaseService schedulerLease, Clock clock,
            @Value("${POD_NAME:}") String podName,
            @Value("${agentteams.task-state-consistency.lease-duration:30s}") java.time.Duration leaseDuration,
            @Value("${agentteams.task-state-consistency.lookback:24h}") java.time.Duration lookback,
            @Value("${agentteams.task-state-consistency.batch-size:100}") int batchSize) {
        return new TaskStateConsistencyJob(service, schedulerLease, clock,
                TaskAssignmentScheduler.defaultOwner(podName), leaseDuration, lookback, batchSize);
    }

    /**
     * The gateway publishes presence from its in-memory connection registry, so a gateway replica that
     * restarts leaves agents marked READY forever, and task admission trusts exactly that column.
     */
    @Bean
    @ConditionalOnProperty(name = "agentteams.agent-presence-consistency.enabled", havingValue = "true", matchIfMissing = true)
    AgentPresenceConsistencyRepository agentPresenceConsistencyRepository(DataSource dataSource) {
        return new JdbcAgentPresenceConsistencyRepository(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.agent-presence-consistency.enabled", havingValue = "true", matchIfMissing = true)
    AgentPresenceConsistencyService agentPresenceConsistencyService(AgentPresenceConsistencyRepository repository) {
        return new AgentPresenceConsistencyService(repository);
    }

    @Bean
    @ConditionalOnProperty(name = "agentteams.agent-presence-consistency.enabled", havingValue = "true", matchIfMissing = true)
    AgentPresenceConsistencyJob agentPresenceConsistencyJob(AgentPresenceConsistencyService service,
            SchedulerLeaseService schedulerLease, Clock clock,
            @Value("${POD_NAME:}") String podName,
            @Value("${agentteams.agent-presence-consistency.lease-duration:30s}") java.time.Duration leaseDuration,
            @Value("${agentteams.agent-presence-consistency.stale-after:2m}") java.time.Duration staleAfter,
            @Value("${agentteams.agent-presence-consistency.batch-size:100}") int batchSize) {
        return new AgentPresenceConsistencyJob(service, schedulerLease, clock,
                TaskAssignmentScheduler.defaultOwner(podName), leaseDuration, staleAfter, batchSize);
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
    ExecutionEventPort executionEventPort(ExecutionEventService executionEvents, DataSource dataSource) {
        return new ControlPlaneExecutionEventAdapter(executionEvents,
                new JdbcModelCallAuditRecorder(new org.springframework.jdbc.core.JdbcTemplate(dataSource)));
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
        return new NatsExecutionEventConsumer(connection, executionEvents, configEvents, objectMapper,
                "control-plane-execution-events", new AsyncConsumerTracing(
                        tracers.getIfAvailable(() -> Tracer.NOOP), tracingPropagator(propagators)));
    }


    @Bean
    @ConditionalOnProperty(name = {"agentteams.nats.enabled", "agentteams.outbox.relay.enabled"},
            havingValue = "true")
    EventPublisher natsEventPublisher(Connection connection, ObjectMapper objectMapper,
            ObjectProvider<Tracer> tracers, ObjectProvider<Propagator> propagators)
            throws IOException {
        return new NatsEventPublisher(connection.jetStream(), objectMapper, new AsyncProducerTracing(
                tracers.getIfAvailable(() -> Tracer.NOOP), tracingPropagator(propagators)));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = {"agentteams.nats.enabled", "agentteams.outbox.relay.enabled"},
            havingValue = "true")
    OutboxRelay outboxRelay(OutboxStore store, EventPublisher publisher, OutboxRelayProperties properties,
            TaskMetricsPort metrics) {
        return new OutboxRelay(store, publisher, properties, Clock.systemUTC(), metrics);
    }

    private static Propagator tracingPropagator(ObjectProvider<Propagator> propagators) {
        return propagators.getIfAvailable(() -> {
            var openTelemetry = GlobalOpenTelemetry.get();
            var w3c = TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(),
                    W3CBaggagePropagator.getInstance());
            return new OtelPropagator(ContextPropagators.create(w3c),
                    openTelemetry.getTracerProvider().get("agentteams-control-plane"));
        });
    }
}
