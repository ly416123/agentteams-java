package io.agentteams.controlplane;

import io.agentteams.controlplane.outbox.EventPublisher;
import io.agentteams.controlplane.outbox.JdbcOutboxStore;
import io.agentteams.controlplane.outbox.NatsEventPublisher;
import io.agentteams.controlplane.outbox.OutboxRelay;
import io.agentteams.controlplane.outbox.OutboxRelayProperties;
import io.agentteams.controlplane.outbox.OutboxStore;
import io.agentteams.controlplane.persistence.FoundationPersistenceService;
import io.agentteams.controlplane.artifact.ArtifactCompletionService;
import io.agentteams.controlplane.artifact.ArtifactService;
import io.agentteams.controlplane.config.ConfigLifecycleRepository;
import io.agentteams.controlplane.config.ConfigSnapshotRepository;
import io.agentteams.controlplane.config.ConfigSnapshotService;
import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import io.agentteams.controlplane.service.SchedulerLeaseService;
import io.agentteams.controlplane.service.TeamService;
import io.agentteams.controlplane.storage.MinioObjectStorage;
import io.agentteams.controlplane.storage.MinioObjectStorageConfig;
import io.agentteams.controlplane.storage.ObjectStorage;
import io.agentteams.controlplane.observability.ControlPlaneMetrics;
import io.agentteams.controlplane.observability.TaskMetricsPort;
import io.nats.client.Connection;
import io.nats.client.Nats;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import io.agentteams.domain.task.TaskTransitionService;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
@EnableScheduling
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
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(MeterRegistry.class)
    ControlPlaneMetrics controlPlaneMetrics(MeterRegistry registry) {
        return new ControlPlaneMetrics(registry);
    }

    @Bean
    TaskMetricsPort taskMetricsPort(ObjectProvider<ControlPlaneMetrics> metrics) {
        ControlPlaneMetrics available = metrics.getIfAvailable();
        return available == null ? TaskMetricsPort.noop() : available;
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(DataSource.class)
    ConfigSnapshotRepository configSnapshotRepository(DataSource dataSource) {
        return new ConfigSnapshotRepository(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(ConfigSnapshotRepository.class)
    ConfigSnapshotService configSnapshotService(ConfigSnapshotRepository repository, Clock clock) {
        return new ConfigSnapshotService(repository, clock);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(DataSource.class)
    ConfigLifecycleRepository configLifecycleRepository(DataSource dataSource) {
        return new ConfigLifecycleRepository(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(DataSource.class)
    SchedulerLeaseRepository schedulerLeaseRepository(DataSource dataSource) {
        return new SchedulerLeaseRepository(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(SchedulerLeaseRepository.class)
    SchedulerLeaseService schedulerLeaseService(SchedulerLeaseRepository repository) {
        return new SchedulerLeaseService(repository);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(FoundationPersistenceService.class)
    TeamService teamService(FoundationPersistenceService persistence) {
        return new TeamService(persistence);
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
    @ConfigurationProperties(prefix = "agentteams.outbox.relay")
    OutboxRelayProperties outboxRelayProperties() {
        return new OutboxRelayProperties();
    }

    @Bean
    OutboxStore outboxStore(FoundationPersistenceService persistence) {
        return new JdbcOutboxStore(persistence);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = {"agentteams.nats.enabled", "agentteams.outbox.relay.enabled"},
            havingValue = "true")
    Connection natsConnection(@Value("${agentteams.nats.url:nats://localhost:4222}") String url)
            throws IOException, InterruptedException {
        return Nats.connect(url);
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
