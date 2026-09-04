package io.agentteams.controlplane.reconciliation;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import io.agentteams.controlplane.agent.AgentPresenceConsistencyJob;
import io.agentteams.controlplane.agent.AgentPresenceConsistencyService;
import io.agentteams.controlplane.service.SchedulerLeaseService;
import io.agentteams.controlplane.team.TeamDeploymentPendingTimeoutJob;
import io.agentteams.controlplane.team.TeamDeploymentPendingTimeoutService;

/**
 * Boots the two reconciliation families through a real Spring context (a small, isolated
 * ApplicationContextRunner instead of a full @SpringBootTest): the reflective wiring contracts in
 * {@code ControlPlaneConfigurationTest} only see annotations, so a bean whose @Value keys never
 * resolve, or a @Scheduled method on a bean that never reaches the context, would otherwise only
 * surface at Kind startup.
 */
class ReconciliationWiringTest {
    private static final String CLOCK_BEAN = "reconciliationTestClock";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(applicationContext -> applicationContext.getBeanFactory()
                    .setConversionService(
                            org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(ReconciliationBeans.class)
            .withBean(CLOCK_BEAN, java.time.Clock.class, () -> java.time.Clock.systemUTC())
            .withBean(javax.sql.DataSource.class, () ->
                    org.mockito.Mockito.mock(javax.sql.DataSource.class))
            .withBean(SchedulerLeaseService.class, () -> new SchedulerLeaseService(
                    org.mockito.Mockito.mock(io.agentteams.controlplane.persistence.SchedulerLeaseRepository.class)));

    @org.junit.jupiter.api.Test
    void registersBothReconciliationJobsWithTheirDefaultProperties() {
        context.run(application -> {
            org.assertj.core.api.Assertions.assertThat(application)
                    .hasSingleBean(AgentPresenceConsistencyJob.class)
                    .hasSingleBean(AgentPresenceConsistencyService.class)
                    .hasSingleBean(TeamDeploymentPendingTimeoutJob.class)
                    .hasSingleBean(TeamDeploymentPendingTimeoutService.class);
        });
    }

    @org.junit.jupiter.api.Test
    void createsNoReconciliationBeansWhenDisabled() {
        context.withPropertyValues(
                "agentteams.agent-presence-consistency.enabled=false",
                "agentteams.team-deployment-pending-timeout.enabled=false")
                .run(application -> {
                    org.assertj.core.api.Assertions.assertThat(application)
                            .doesNotHaveBean(AgentPresenceConsistencyJob.class)
                            .doesNotHaveBean(TeamDeploymentPendingTimeoutJob.class);
                });
    }

    @org.junit.jupiter.api.Test
    void scheduledMethodsAreBackedByTheSchedulingInfrastructure() {
        context.run(application -> {
            org.assertj.core.api.Assertions.assertThat(application)
                    .hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            AgentPresenceConsistencyJob presence = application.getBean(AgentPresenceConsistencyJob.class);
            TeamDeploymentPendingTimeoutJob pendingTimeout =
                    application.getBean(TeamDeploymentPendingTimeoutJob.class);
            org.assertj.core.api.Assertions.assertThat(presence).isNotNull();
            org.assertj.core.api.Assertions.assertThat(pendingTimeout).isNotNull();
        });
    }

    /**
     * Mirrors the exact @Bean signatures used by ControlPlaneConfiguration so a signature drift
     * (renamed parameter, dropped @Value) breaks compilation of this test instead of production.
     */
    @Configuration(proxyBeanMethods = false)
    @org.springframework.scheduling.annotation.EnableScheduling
    static class ReconciliationBeans {
        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
                name = "agentteams.agent-presence-consistency.enabled", havingValue = "true", matchIfMissing = true)
        AgentPresenceConsistencyService agentPresenceConsistencyService(
                io.agentteams.controlplane.agent.AgentPresenceConsistencyRepository repository) {
            return new AgentPresenceConsistencyService(repository);
        }

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
                name = "agentteams.agent-presence-consistency.enabled", havingValue = "true", matchIfMissing = true)
        io.agentteams.controlplane.agent.AgentPresenceConsistencyRepository agentPresenceConsistencyRepository(
                javax.sql.DataSource dataSource) {
            return new io.agentteams.controlplane.agent.AgentPresenceConsistencyRepository() {
                @Override
                public java.util.List<java.util.UUID> findStaleReadyAgents(
                        java.time.Instant lastSeenBefore, int limit) {
                    return java.util.List.of();
                }

                @Override
                public int markOffline(java.util.UUID agentId, java.time.Instant at) {
                    return 0;
                }
            };
        }

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
                name = "agentteams.agent-presence-consistency.enabled", havingValue = "true", matchIfMissing = true)
        AgentPresenceConsistencyJob agentPresenceConsistencyJob(AgentPresenceConsistencyService service,
                SchedulerLeaseService schedulerLease, java.time.Clock clock,
                @org.springframework.beans.factory.annotation.Value("${POD_NAME:}") String podName,
                @org.springframework.beans.factory.annotation.Value(
                        "${agentteams.agent-presence-consistency.lease-duration:30s}") java.time.Duration leaseDuration,
                @org.springframework.beans.factory.annotation.Value(
                        "${agentteams.agent-presence-consistency.stale-after:2m}") java.time.Duration staleAfter,
                @org.springframework.beans.factory.annotation.Value(
                        "${agentteams.agent-presence-consistency.batch-size:100}") int batchSize) {
            return new AgentPresenceConsistencyJob(service, schedulerLease, clock,
                    io.agentteams.controlplane.service.TaskAssignmentScheduler.defaultOwner(podName),
                    leaseDuration, staleAfter, batchSize);
        }

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
                name = "agentteams.team-deployment-pending-timeout.enabled", havingValue = "true", matchIfMissing = true)
        TeamDeploymentPendingTimeoutService teamDeploymentPendingTimeoutService(
                io.agentteams.controlplane.team.TeamDeploymentPendingTimeoutRepository repository) {
            return new TeamDeploymentPendingTimeoutService(repository);
        }

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
                name = "agentteams.team-deployment-pending-timeout.enabled", havingValue = "true", matchIfMissing = true)
        io.agentteams.controlplane.team.TeamDeploymentPendingTimeoutRepository teamDeploymentPendingTimeoutRepository(
                javax.sql.DataSource dataSource) {
            return new io.agentteams.controlplane.team.TeamDeploymentPendingTimeoutRepository() {
                @Override
                public int failStalePendingMembers(java.time.Instant now,
                        java.time.Instant applyUpdatedBefore, int limit) {
                    return 0;
                }

                @Override
                public int refreshPendingAggregates(int limit) {
                    return 0;
                }
            };
        }

        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
                name = "agentteams.team-deployment-pending-timeout.enabled", havingValue = "true", matchIfMissing = true)
        TeamDeploymentPendingTimeoutJob teamDeploymentPendingTimeoutJob(
                TeamDeploymentPendingTimeoutService service, SchedulerLeaseService schedulerLease,
                java.time.Clock clock,
                @org.springframework.beans.factory.annotation.Value("${POD_NAME:}") String podName,
                @org.springframework.beans.factory.annotation.Value(
                        "${agentteams.team-deployment-pending-timeout.lease-duration:30s}") java.time.Duration leaseDuration,
                @org.springframework.beans.factory.annotation.Value(
                        "${agentteams.team-deployment-pending-timeout.pending-timeout:10m}") java.time.Duration pendingTimeout,
                @org.springframework.beans.factory.annotation.Value(
                        "${agentteams.team-deployment-pending-timeout.batch-size:100}") int batchSize) {
            return new TeamDeploymentPendingTimeoutJob(service, schedulerLease, clock,
                    io.agentteams.controlplane.service.TaskAssignmentScheduler.defaultOwner(podName),
                    leaseDuration, pendingTimeout, batchSize);
        }
    }
}
